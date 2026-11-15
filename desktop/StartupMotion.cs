using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace DreamShield.Desktop
{
    // The policy has no readiness delay. It animates while real work happens,
    // rests as a static brand after 570 ms, and exits as soon as the view is ready.
    internal static class StartupMotionPolicy
    {
        internal const double IntroMs = 570;
        internal const double RevealMs = 140;
        internal static StartupFrame Frame(double elapsed, double? readyElapsed, bool animate, bool visible)
        {
            if (!visible) return new StartupFrame(1, readyElapsed.HasValue ? 0 : 1, false, readyElapsed.HasValue);
            if (!animate) return new StartupFrame(1, readyElapsed.HasValue ? 0 : 1, false, readyElapsed.HasValue);
            double progress = EaseOut(Clamp(elapsed / IntroMs));
            if (!readyElapsed.HasValue) return new StartupFrame(progress, 1, elapsed < IntroMs, false);
            double exit = Clamp(readyElapsed.Value / RevealMs);
            double completedStroke = progress + (1 - progress) * EaseOut(exit);
            return new StartupFrame(completedStroke, 1 - exit * exit * (3 - 2 * exit), exit < 1, exit >= 1);
        }
        internal static double Clamp(double value) { return Math.Max(0, Math.Min(1, value)); }
        internal static double EaseOut(double value) { return 1 - Math.Pow(1 - value, 3); }
    }
    internal sealed class StartupFrame
    {
        internal readonly double Progress;
        internal readonly double Opacity;
        internal readonly bool NeedsTimer;
        internal readonly bool Complete;
        internal StartupFrame(double progress, double opacity, bool timer, bool complete) { Progress = progress; Opacity = opacity; NeedsTimer = timer; Complete = complete; }
    }

    // This is a separate owned top-level layered window, not an opaque Panel.
    // Form.Opacity is composed by Windows above the WebView2 child HWND.
    internal sealed class StartupSplash : Form
    {
        private readonly Form owner;
        private readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        private readonly Stopwatch clock = new Stopwatch();
        private readonly Stopwatch reveal = new Stopwatch();
        private bool animate;
        private bool dark;
        private bool settled;
        private bool completed;
        private double progress;
        private float paintDpi = 96F;
        private string stage = "正在启动本地服务…";
        internal StartupSplash(Form owner, bool dark)
        {
            this.owner = owner; this.dark = dark;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            DoubleBuffered = true;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            animate = AnimationAllowed();
            ApplyBackground();
            AccessibleRole = AccessibleRole.Pane;
            AccessibleName = "梦盾";
            AccessibleDescription = stage;
            timer.Interval = 16;
            timer.Tick += delegate { RefreshFrame(); };
            owner.LocationChanged += OwnerBoundsChanged;
            owner.SizeChanged += OwnerBoundsChanged;
            owner.VisibleChanged += OwnerVisibilityChanged;
            owner.FormClosed += OwnerClosed;
            Microsoft.Win32.SystemEvents.UserPreferenceChanged += UserPreferenceChanged;
        }
        protected override bool ShowWithoutActivation { get { return true; } }
        protected override CreateParams CreateParams
        {
            get { var parameters = base.CreateParams; parameters.ExStyle |= 0x08000000 | 0x00000080; return parameters; }
        }
        internal void Begin()
        {
            clock.Start();
            MoveToOwner();
            Show(owner);
            RefreshFrame();
        }
        internal void SetStage(string text) { stage = text; AccessibleDescription = text; Invalidate(); }
        internal void SetTheme(bool value) { dark = value; ApplyBackground(); Invalidate(); }
        private void ApplyBackground()
        {
            BackColor = SystemInformation.HighContrast ? SystemColors.Window : dark ? Color.FromArgb(10, 18, 27) : Color.FromArgb(246, 248, 250);
        }
        internal void Ready()
        {
            if (completed) return;
            reveal.Start();
            RefreshFrame();
        }
        internal void Cancel()
        {
            if (completed) return;
            completed = true;
            timer.Stop();
            Close();
        }
        private void RefreshFrame()
        {
            if (completed || IsDisposed) return;
            bool onScreen = owner.Visible && owner.WindowState != FormWindowState.Minimized;
            var frame = StartupMotionPolicy.Frame(settled ? Math.Max(clock.Elapsed.TotalMilliseconds, StartupMotionPolicy.IntroMs) : clock.Elapsed.TotalMilliseconds,
                reveal.IsRunning ? (double?)reveal.Elapsed.TotalMilliseconds : null, animate, onScreen);
            if (frame.Complete) { Cancel(); return; }
            if (onScreen)
            {
                if (!Visible) Show(owner);
                if (Opacity != frame.Opacity) Opacity = frame.Opacity;
                if (progress != frame.Progress)
                {
                    progress = frame.Progress;
                    Invalidate(StartupPainter.EmblemArea(ClientRectangle, paintDpi));
                }
            }
            if (frame.NeedsTimer) timer.Start(); else timer.Stop();
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            paintDpi = e.Graphics.DpiX;
            StartupPainter.Draw(e.Graphics, ClientRectangle, progress, dark, SystemInformation.HighContrast, stage, e.ClipRectangle);
        }
        private void MoveToOwner()
        {
            if (owner.IsDisposed || !owner.IsHandleCreated) return;
            Bounds = owner.RectangleToScreen(owner.ClientRectangle);
        }
        private void OwnerBoundsChanged(object sender, EventArgs e) { MoveToOwner(); OwnerVisibilityChanged(sender, e); }
        private void OwnerVisibilityChanged(object sender, EventArgs e)
        {
            if (completed) return;
            if (!owner.Visible || owner.WindowState == FormWindowState.Minimized)
            {
                settled = true; timer.Stop(); Hide();
                if (reveal.IsRunning) Cancel();
            }
            else { MoveToOwner(); RefreshFrame(); }
        }
        private void OwnerClosed(object sender, FormClosedEventArgs e) { Cancel(); }
        private void UserPreferenceChanged(object sender, Microsoft.Win32.UserPreferenceChangedEventArgs e)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(new Action(delegate { animate = AnimationAllowed(); ApplyBackground(); RefreshFrame(); })); }
            catch (InvalidOperationException) { /* The owner is closing; no animation work remains. */ }
        }
        internal static bool AnimationAllowed()
        {
            int enabled;
            return !SystemInformation.HighContrast && NativeMotion.SystemParametersInfo(0x1042, 0, out enabled, 0) && enabled != 0;
        }
        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            timer.Stop(); timer.Dispose();
            owner.LocationChanged -= OwnerBoundsChanged;
            owner.SizeChanged -= OwnerBoundsChanged;
            owner.VisibleChanged -= OwnerVisibilityChanged;
            owner.FormClosed -= OwnerClosed;
            Microsoft.Win32.SystemEvents.UserPreferenceChanged -= UserPreferenceChanged;
            base.OnFormClosed(e);
        }
    }

    internal static class StartupPainter
    {
        internal static Rectangle EmblemArea(Rectangle bounds, float dpiX)
        {
            float scale = dpiX / 96F;
            var area = RectangleF.FromLTRB(bounds.Width / 2F - 56F * scale, bounds.Height / 2F - 120F * scale,
                                           bounds.Width / 2F + 56F * scale, bounds.Height / 2F - 8F * scale);
            return Rectangle.Intersect(bounds, Rectangle.Ceiling(area));
        }
        internal static void Draw(Graphics graphics, Rectangle bounds, double progress, bool dark, bool highContrast, string stage, Rectangle? clip = null)
        {
            var background = highContrast ? SystemColors.Window : dark ? Color.FromArgb(10, 18, 27) : Color.FromArgb(246, 248, 250);
            var foreground = highContrast ? SystemColors.WindowText : dark ? Color.FromArgb(233, 240, 245) : Color.FromArgb(29, 45, 56);
            var muted = highContrast ? SystemColors.WindowText : dark ? Color.FromArgb(163, 181, 195) : Color.FromArgb(77, 97, 112);
            var accent = highContrast ? SystemColors.Highlight : dark ? Color.FromArgb(130, 217, 208) : Color.FromArgb(24, 107, 99);
            var dirty = clip ?? bounds;
            using (var fill = new SolidBrush(background)) graphics.FillRectangle(fill, dirty);
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.TextRenderingHint = System.Drawing.Text.TextRenderingHint.ClearTypeGridFit;
            float dpi = graphics.DpiX / 96F;
            float size = 94F * dpi;
            float centerX = bounds.Width / 2F;
            float centerY = bounds.Height / 2F - 28F * dpi;
            float settleScale = (float)(1 + .025 * (1 - progress));
            var state = graphics.Save();
            graphics.TranslateTransform(centerX, centerY - 36F * dpi);
            graphics.ScaleTransform(size * settleScale / 24F, size * settleScale / 24F);
            graphics.TranslateTransform(-12, -12);
            using (var shield = Shield())
            using (var moon = Moon())
            using (var guide = new Pen(Color.FromArgb(highContrast ? 90 : 34, accent), 1.15F))
            using (var line = new Pen(accent, 1.15F))
            {
                guide.LineJoin = line.LineJoin = LineJoin.Round;
                guide.StartCap = guide.EndCap = line.StartCap = line.EndCap = LineCap.Round;
                graphics.DrawPath(guide, shield); graphics.DrawPath(guide, moon);
                Stroke(graphics, shield, line, StartupMotionPolicy.Clamp(progress / .76));
                Stroke(graphics, moon, line, StartupMotionPolicy.Clamp((progress - .32) / .68));
            }
            graphics.Restore(state);
            if (dirty.Bottom <= centerY + 28F * dpi) return;
            using (var titleFont = new Font("Microsoft YaHei UI", 23F, FontStyle.Regular, GraphicsUnit.Point))
            using (var stageFont = new Font("Microsoft YaHei UI", 10F, FontStyle.Regular, GraphicsUnit.Point))
            using (var ink = new SolidBrush(foreground))
            using (var soft = new SolidBrush(muted))
            using (var alignment = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
            {
                graphics.DrawString("梦盾", titleFont, ink, new RectangleF(0, centerY + 28F * dpi, bounds.Width, 46F * dpi), alignment);
                graphics.DrawString(stage, stageFont, soft, new RectangleF(0, centerY + 82F * dpi, bounds.Width, 28F * dpi), alignment);
            }
        }
        private static GraphicsPath Shield()
        {
            var path = new GraphicsPath();
            path.AddLines(new[] { new PointF(12, 2), new PointF(3.5F, 5.5F), new PointF(3.5F, 11.5F) });
            path.AddBezier(3.5F, 11.5F, 3.5F, 16.7F, 7.7F, 20.3F, 12, 22);
            path.AddBezier(12, 22, 16.3F, 20.3F, 20.5F, 16.7F, 20.5F, 11.5F);
            path.AddLines(new[] { new PointF(20.5F, 11.5F), new PointF(20.5F, 5.5F), new PointF(12, 2) });
            return path;
        }
        private static GraphicsPath Moon()
        {
            var path = new GraphicsPath();
            path.AddBezier(15.5F, 7, 11, 5.5F, 8, 8.6F, 8.5F, 12.3F);
            path.AddBezier(8.5F, 12.3F, 9, 16, 13, 17.5F, 17, 14);
            path.AddBezier(17, 14, 12.8F, 14.8F, 11.3F, 10, 15.5F, 7);
            return path;
        }
        private static void Stroke(Graphics graphics, GraphicsPath original, Pen pen, double progress)
        {
            if (progress <= 0) return;
            if (progress >= 1) { graphics.DrawPath(pen, original); return; }
            using (var flat = (GraphicsPath)original.Clone())
            {
                flat.Flatten(null, .04F);
                var points = flat.PathPoints;
                if (points.Length < 2) return;
                double length = 0;
                for (int i = 1; i < points.Length; ++i) length += Distance(points[i - 1], points[i]);
                double remaining = length * progress;
                var visible = new List<PointF>(); visible.Add(points[0]);
                for (int i = 1; i < points.Length; ++i)
                {
                    double segment = Distance(points[i - 1], points[i]);
                    if (remaining >= segment) { visible.Add(points[i]); remaining -= segment; }
                    else
                    {
                        double part = segment == 0 ? 0 : remaining / segment;
                        visible.Add(new PointF((float)(points[i - 1].X + (points[i].X - points[i - 1].X) * part), (float)(points[i - 1].Y + (points[i].Y - points[i - 1].Y) * part)));
                        break;
                    }
                }
                if (visible.Count > 1) graphics.DrawLines(pen, visible.ToArray());
            }
        }
        private static double Distance(PointF a, PointF b) { return Math.Sqrt((b.X - a.X) * (b.X - a.X) + (b.Y - a.Y) * (b.Y - a.Y)); }
    }

    internal static class NativeMotion
    {
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool SystemParametersInfo(uint action, uint parameter, out int value, uint flags);
    }
}
