using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace DreamShield.Desktop
{
    internal static class Program
    {
        internal static readonly string LocalRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DreamShield");
        internal static readonly uint ActivateMessage = Native.RegisterWindowMessage("DreamShield.Desktop.Activate." + WindowsIdentity.GetCurrent().User.Value);

        [STAThread]
        private static int Main(string[] args)
        {
            bool created;
            using (var mutex = new Mutex(true, "Local\\DreamShield.Desktop." + WindowsIdentity.GetCurrent().User.Value, out created))
            {
                if (!created)
                {
                    Native.PostMessage(new IntPtr(0xffff), ActivateMessage, IntPtr.Zero, IntPtr.Zero);
                    return 0;
                }
                try
                {
                    try { Native.SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch (EntryPointNotFoundException) { Native.SetProcessDPIAware(); }
                    Application.EnableVisualStyles();
                    Application.SetCompatibleTextRenderingDefault(false);
                    Directory.CreateDirectory(LocalRoot);
                    Application.Run(new ConsoleWindow());
                    return 0;
                }
                catch (Exception error)
                {
                    HostLog.Write("startup", error);
                    MessageBox.Show("梦盾暂时无法启动。请查看本机日志后重试。\n\n" + HostLog.LogDirectory, "梦盾", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
            }
        }
    }

    internal sealed class ConsoleWindow : Form
    {
        private readonly WebView2 web = new WebView2();
        private readonly Panel loading = new Panel();
        private readonly Label message = new Label();
        private readonly Button retry = new Button();
        private readonly NotifyIcon tray = new NotifyIcon();
        private readonly ToolStripMenuItem exitItem = new ToolStripMenuItem();
        private readonly BackendService backend = new BackendService();
        private bool quitting;
        private bool initializing;
        private bool webReady;
        private bool notified;
        private bool dark;
        private StartupSplash splash;

        internal ConsoleWindow()
        {
            Text = "梦盾 · DreamShield";
            ClientSize = new Size(1360, 900);
            MinimumSize = new Size(1000, 680);
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Microsoft YaHei UI", 9F);
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            web.Dock = DockStyle.Fill;
            Controls.Add(web);
            loading.Dock = DockStyle.Fill;
            message.AutoSize = false;
            message.TextAlign = ContentAlignment.MiddleCenter;
            message.Font = new Font("Microsoft YaHei UI", 13F);
            message.Text = "正在打开梦盾…";
            message.Dock = DockStyle.Fill;
            retry.Text = "重试";
            retry.Size = new Size(112, 38);
            retry.Anchor = AnchorStyles.Bottom;
            retry.Visible = false;
            retry.Click += async delegate { await InitializeAsync(); };
            loading.Controls.Add(message);
            loading.Controls.Add(retry);
            Controls.Add(loading);
            loading.BringToFront();
            loading.Resize += delegate { retry.Location = new Point((loading.Width - retry.Width) / 2, loading.Height / 2 + 65); };
            var menu = new ContextMenuStrip();
            menu.Items.Add("打开梦盾", null, delegate { RestoreWindow(); });
            menu.Items.Add("打开日志文件夹", null, delegate { Directory.CreateDirectory(HostLog.LogDirectory); Process.Start("explorer.exe", HostLog.LogDirectory); });
            menu.Items.Add(new ToolStripSeparator());
            exitItem.Text = "退出桌面窗口（后台服务继续）";
            exitItem.Click += delegate { ExitDesktop(); };
            menu.Items.Add(exitItem);
            tray.Text = "梦盾 · 双击打开控制台";
            tray.Icon = Icon;
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += delegate { RestoreWindow(); };
            tray.Visible = true;
            ApplyTheme(HostPolicy.SystemDark());
            Shown += async delegate { ClampToWorkingArea(); await InitializeAsync(); };
            FormClosing += delegate(object sender, FormClosingEventArgs e)
            {
                if (quitting || e.CloseReason == CloseReason.WindowsShutDown || e.CloseReason == CloseReason.TaskManagerClosing) return;
                e.Cancel = true;
                Hide();
                if (!notified)
                {
                    notified = true;
                    tray.ShowBalloonTip(3500, "梦盾仍在后台运行", "双击托盘图标重新打开；右键菜单可退出。", ToolTipIcon.Info);
                }
                backend.WriteStatus("tray");
            };
            FormClosed += delegate { CancelSplash(); tray.Visible = false; tray.Dispose(); web.Dispose(); backend.Dispose(); };
        }

        private async Task InitializeAsync()
        {
            if (initializing) return;
            initializing = true;
            retry.Visible = false;
            message.Text = "正在打开梦盾…";
            loading.Visible = true;
            loading.BringToFront();
            CancelSplash();
            splash = new StartupSplash(this, dark);
            splash.Begin();
            try
            {
                await backend.EnsureReadyAsync();
                if (splash != null) splash.SetStage("正在打开控制台…");
                exitItem.Text = backend.OwnsProcess ? "退出并停止本次启动的服务" : "退出桌面窗口（后台服务继续）";
                if (!webReady)
                {
                    var profile = Path.Combine(Program.LocalRoot, "WebView2");
                    var environment = await CoreWebView2Environment.CreateAsync(null, profile, null);
                    await web.EnsureCoreWebView2Async(environment);
                    web.CoreWebView2.Settings.AreDevToolsEnabled = false;
                    web.CoreWebView2.Settings.IsStatusBarEnabled = false;
                    web.CoreWebView2.Settings.IsZoomControlEnabled = true;
                    web.CoreWebView2.NavigationStarting += OnNavigation;
                    web.CoreWebView2.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs e)
                    {
                        e.Handled = true;
                        if (e.IsUserInitiated) HostPolicy.OpenWebLink(e.Uri);
                    };
                    web.CoreWebView2.WebMessageReceived += delegate(object sender, CoreWebView2WebMessageReceivedEventArgs e)
                    {
                        bool selected;
                        if (HostPolicy.TryTheme(e.Source, e.WebMessageAsJson, out selected)) ApplyTheme(selected);
                    };
                    web.CoreWebView2.NavigationCompleted += delegate(object sender, CoreWebView2NavigationCompletedEventArgs e)
                    {
                        if (e.IsSuccess)
                        {
                            // Reveal the already painted WebView, not another opaque loading Panel.
                            loading.Visible = false;
                            if (splash != null) splash.Ready();
                            backend.WriteStatus("window");
                        }
                        else ShowError("控制台暂时无法打开，请重试。", e.WebErrorStatus.ToString());
                    };
                    web.CoreWebView2.ProcessFailed += delegate(object sender, CoreWebView2ProcessFailedEventArgs e)
                    {
                        ShowError("显示窗口暂时不可用，请重新打开梦盾。", e.ProcessFailedKind.ToString());
                    };
                    webReady = true;
                }
                web.CoreWebView2.Navigate(BackendService.BaseUrl + "/");
            }
            catch (WebView2RuntimeNotFoundException error)
            {
                ShowError("缺少 Microsoft Edge WebView2 运行时。安装后重新打开梦盾。", error.GetType().Name);
            }
            catch (OutdatedServerException error)
            {
                ShowError("本机梦盾服务需要更新。请退出旧版服务后重试。", error.Message);
            }
            catch (Exception error)
            {
                ShowError("梦盾暂时无法打开。请确认本机服务可用后重试。", error.ToString());
            }
            finally { initializing = false; }
        }

        private void ClampToWorkingArea()
        {
            var area = Screen.FromControl(this).WorkingArea;
            MinimumSize = new Size(Math.Min(1000, Math.Max(640, area.Width - 32)), Math.Min(680, Math.Max(480, area.Height - 32)));
            Size = new Size(Math.Min(Width, area.Width - 32), Math.Min(Height, area.Height - 32));
            Location = new Point(area.Left + (area.Width - Width) / 2, area.Top + (area.Height - Height) / 2);
        }

        private void OnNavigation(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            if (HostPolicy.IsOwnerUrl(e.Uri)) return;
            e.Cancel = true;
            if (e.IsUserInitiated) HostPolicy.OpenWebLink(e.Uri);
        }

        private void ShowError(string text, string detail)
        {
            CancelSplash();
            HostLog.Write("window", detail);
            message.Text = text;
            retry.Visible = true;
            loading.Visible = true;
            loading.BringToFront();
        }

        private void CancelSplash()
        {
            if (splash == null) return;
            splash.Cancel();
            splash.Dispose();
            splash = null;
        }

        private void ApplyTheme(bool value)
        {
            dark = value;
            if (splash != null && !splash.IsDisposed) splash.SetTheme(value);
            var background = dark ? Color.FromArgb(10, 18, 27) : Color.FromArgb(246, 248, 250);
            BackColor = background;
            loading.BackColor = background;
            message.ForeColor = dark ? Color.FromArgb(233, 240, 245) : Color.FromArgb(29, 45, 56);
            retry.BackColor = dark ? Color.FromArgb(25, 43, 58) : Color.White;
            retry.ForeColor = message.ForeColor;
            web.DefaultBackgroundColor = background;
            if (IsHandleCreated)
            {
                int mode = dark ? 1 : 0;
                Native.DwmSetWindowAttribute(Handle, 20, ref mode, sizeof(int));
            }
        }

        protected override void OnHandleCreated(EventArgs e) { base.OnHandleCreated(e); ApplyTheme(dark); }
        protected override void WndProc(ref Message m)
        {
            if ((uint)m.Msg == Program.ActivateMessage) { RestoreWindow(); return; }
            base.WndProc(ref m);
        }
        private void RestoreWindow()
        {
            if (InvokeRequired) { BeginInvoke(new Action(RestoreWindow)); return; }
            Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            Native.SetForegroundWindow(Handle);
            backend.WriteStatus("window");
        }
        private void ExitDesktop()
        {
            quitting = true;
            backend.StopOwnedProcess();
            Close();
        }
    }

    internal sealed class BackendService : IDisposable
    {
        internal const string BaseUrl = "http://127.0.0.1:8000";
        private readonly HttpClient client;
        private Process process;
        internal bool OwnsProcess { get; private set; }
        private string dataDirectory;
        private string lastWrittenState;
        internal BackendService()
        {
            client = new HttpClient(new HttpClientHandler { UseProxy = false });
            client.Timeout = TimeSpan.FromSeconds(2);
        }
        internal async Task EnsureReadyAsync()
        {
            if (await IsDreamShieldAsync())
            {
                if (!await HasCurrentConsoleAsync()) throw new OutdatedServerException();
                WriteStatus("ready"); return;
            }
            if (await IsPortOccupiedAsync()) throw new IOException("Port 8000 is occupied by another local application.");
            var backendRoot = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "backend");
            if (!File.Exists(Path.Combine(backendRoot, "app", "server.py")))
                backendRoot = Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "backend"));
            var executable = Path.Combine(backendRoot, "DreamShield.Server.exe");
            if (!File.Exists(executable))
            {
                executable = Environment.GetEnvironmentVariable("DREAMSHIELD_PYTHON");
                if (String.IsNullOrWhiteSpace(executable) || !Path.IsPathRooted(executable) || !File.Exists(executable))
                    throw new FileNotFoundException("Run Start-Desktop.ps1 with the project Python environment.");
            }
            dataDirectory = HostPolicy.ResolveDataDirectory(Program.LocalRoot);
            Directory.CreateDirectory(dataDirectory);
            var serverScript = Path.Combine(backendRoot, "app", "server.py");
            var info = new ProcessStartInfo(executable);
            info.WorkingDirectory = backendRoot;
            info.UseShellExecute = false;
            info.CreateNoWindow = true;
            info.WindowStyle = ProcessWindowStyle.Hidden;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            if (File.Exists(serverScript)) info.Arguments = "-X utf8 -B -u \"" + serverScript + "\"";
            info.EnvironmentVariables["DREAMSHIELD_HOST"] = "127.0.0.1";
            info.EnvironmentVariables["DREAMSHIELD_PORT"] = "8000";
            info.EnvironmentVariables["DREAMSHIELD_MOBILE"] = "1";
            info.EnvironmentVariables["DREAMSHIELD_MOBILE_PORT"] = "8743";
            info.EnvironmentVariables["DREAMSHIELD_NO_BROWSER"] = "1";
            info.EnvironmentVariables["DREAMSHIELD_DATA_DIR"] = dataDirectory;
            info.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
            foreach (var setting in HostPolicy.RuntimeOptions(Program.LocalRoot)) info.EnvironmentVariables[setting.Key] = setting.Value;
            var lan = HostPolicy.PreferredLanAddress();
            if (!String.IsNullOrEmpty(lan)) info.EnvironmentVariables["DREAMSHIELD_MOBILE_LAN_ADDRESS"] = lan;
            process = new Process { StartInfo = info, EnableRaisingEvents = true };
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) HostLog.Write("server", e.Data); };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) HostLog.Write("server-error", e.Data); };
            if (!process.Start()) throw new IOException("The local server could not start.");
            OwnsProcess = true;
            process.BeginOutputReadLine(); process.BeginErrorReadLine();
            for (int attempt = 0; attempt < 60; ++attempt)
            {
                if (process.HasExited) throw new IOException("The local server exited before it became ready.");
                if (await IsDreamShieldAsync())
                {
                    if (!await HasCurrentConsoleAsync()) throw new OutdatedServerException();
                    WriteStatus("ready"); return;
                }
                await Task.Delay(400);
            }
            throw new TimeoutException("The local server did not become ready.");
        }
        private async Task<bool> HasCurrentConsoleAsync()
        {
            try
            {
                using (var response = await client.GetAsync(BaseUrl + "/static/console_motion.css?v=testprobe"))
                {
                    var type = response.Content.Headers.ContentType;
                    IEnumerable<string> versions;
                    if (!response.IsSuccessStatusCode || type == null || type.MediaType != "text/css" ||
                        !response.Headers.TryGetValues("X-DreamShield-Console", out versions)) return false;
                    foreach (var value in versions)
                        if (HostPolicy.AcceptsConsoleVersion(value)) return true;
                    return false;
                }
            }
            catch { return false; }
        }
        private async Task<bool> IsDreamShieldAsync()
        {
            try
            {
                var response = await client.GetAsync(BaseUrl + "/api/app/status");
                if (!response.IsSuccessStatusCode) return false;
                var text = await response.Content.ReadAsStringAsync();
                var data = new JavaScriptSerializer().DeserializeObject(text) as Dictionary<string, object>;
                object config;
                if (data == null || !data.TryGetValue("config", out config) || !data.ContainsKey("vlm_model")) return false;
                var settings = config as Dictionary<string, object>;
                return settings != null && settings.ContainsKey("device_base_url");
            }
            catch { return false; }
        }
        private async Task<bool> IsPortOccupiedAsync()
        {
            using (var socket = new System.Net.Sockets.TcpClient())
            {
                try
                {
                    var connect = socket.ConnectAsync("127.0.0.1", 8000);
                    if (await Task.WhenAny(connect, Task.Delay(500)) != connect) return false;
                    await connect;
                    return true;
                }
                catch { return false; }
            }
        }
        internal void WriteStatus(string state)
        {
            if (state == lastWrittenState) return;
            try
            {
                var report = new Dictionary<string, object> {
                    {"host_version", "0.1.5"}, {"timestamp", DateTimeOffset.UtcNow.ToString("o")},
                    {"state", state}, {"backend_mode", OwnsProcess ? "started" : "reused"},
                    {"backend_pid", OwnsProcess && process != null ? (object)process.Id : null},
                    {"data_directory", dataDirectory}, {"base_url", BaseUrl}
                };
                Directory.CreateDirectory(Program.LocalRoot);
                File.WriteAllText(Path.Combine(Program.LocalRoot, "desktop-host-status.json"), new JavaScriptSerializer().Serialize(report));
                lastWrittenState = state;
            }
            catch { }
        }
        internal void StopOwnedProcess()
        {
            if (!OwnsProcess || process == null) return;
            try { if (!process.HasExited) process.Kill(); } catch (Exception e) { HostLog.Write("stop", e); }
        }
        public void Dispose() { client.Dispose(); if (process != null) process.Dispose(); }
    }

    internal static class HostPolicy
    {
        internal static bool AcceptsConsoleVersion(string value)
        {
            Version version;
            return Version.TryParse(value, out version) && version.Major == 0 && version >= new Version(0, 1, 5);
        }

        internal static bool IsOwnerUrl(string value)
        {
            Uri uri;
            return Uri.TryCreate(value, UriKind.Absolute, out uri) && uri.Scheme == "http" &&
                uri.Host == "127.0.0.1" && uri.Port == 8000 && String.IsNullOrEmpty(uri.UserInfo);
        }
        internal static bool TryTheme(string source, string json, out bool dark)
        {
            dark = false;
            if (!IsOwnerUrl(source)) return false;
            try
            {
                var value = new JavaScriptSerializer().DeserializeObject(json) as Dictionary<string, object>;
                if (value == null || value.Count != 2 || !value.ContainsKey("type") || !value.ContainsKey("value") || !Object.Equals(value["type"], "theme")) return false;
                if (Object.Equals(value["value"], "dark")) { dark = true; return true; }
                return Object.Equals(value["value"], "light");
            }
            catch { return false; }
        }
        internal static bool SystemDark()
        {
            try
            {
                using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"))
                    return key != null && Object.Equals(key.GetValue("AppsUseLightTheme"), 0);
            }
            catch { return false; }
        }
        internal static string ResolveDataDirectory(string localRoot)
        {
            var configuration = Path.Combine(localRoot, "desktop.json");
            if (File.Exists(configuration))
            {
                var text = File.ReadAllText(configuration);
                var value = new JavaScriptSerializer().DeserializeObject(text) as Dictionary<string, object>;
                object target;
                if (value != null && value.TryGetValue("data_dir", out target) && target is string && !String.IsNullOrWhiteSpace((string)target))
                {
                    if (!Path.IsPathRooted((string)target)) throw new IOException("Data directory must be an absolute path.");
                    return Path.GetFullPath((string)target);
                }
            }
            return Path.Combine(localRoot, "data");
        }
        internal static Dictionary<string, string> RuntimeOptions(string localRoot)
        {
            var result = new Dictionary<string, string>();
            var path = Path.Combine(localRoot, "desktop.json");
            if (!File.Exists(path)) return result;
            var config = new JavaScriptSerializer().DeserializeObject(File.ReadAllText(path)) as Dictionary<string, object>;
            if (config == null) return result;
            object raw;
            if (config.TryGetValue("vlm_api_key_file", out raw) && raw is string && !String.IsNullOrWhiteSpace((string)raw))
            {
                if (!Path.IsPathRooted((string)raw)) throw new IOException("API key file path must be absolute.");
                result["DREAMSHIELD_VLM_KEY_FILE"] = Path.GetFullPath((string)raw);
            }
            if (config.TryGetValue("vlm_provider", out raw) && raw is string && !String.IsNullOrWhiteSpace((string)raw))
            {
                var provider = ((string)raw).Trim().ToLowerInvariant();
                if (provider != "deepseek" && provider != "openai") throw new IOException("Unsupported model provider in desktop.json.");
                result["VLM_PROVIDER"] = provider;
            }
            if (config.TryGetValue("vlm_model", out raw) && raw is string && !String.IsNullOrWhiteSpace((string)raw))
            {
                var model = ((string)raw).Trim();
                if (model.Length > 128) throw new IOException("Model name in desktop.json is too long.");
                foreach (var ch in model) if (Char.IsControl(ch)) throw new IOException("Invalid model name in desktop.json.");
                result["VLM_MODEL"] = model;
            }
            if (config.TryGetValue("vlm_api_url", out raw) && raw is string && !String.IsNullOrWhiteSpace((string)raw))
            {
                Uri uri;
                if (!Uri.TryCreate((string)raw, UriKind.Absolute, out uri) || uri.Scheme != "https" || !String.IsNullOrEmpty(uri.UserInfo)) throw new IOException("Model endpoint in desktop.json must be an HTTPS URL without user information.");
                result["VLM_API_URL"] = uri.AbsoluteUri;
            }
            return result;
        }
        internal static string PreferredLanAddress()
        {
            try
            {
                var adapters = new List<System.Net.NetworkInformation.NetworkInterface>(System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces());
                adapters.Sort(delegate(System.Net.NetworkInformation.NetworkInterface a, System.Net.NetworkInformation.NetworkInterface b)
                {
                    return (a.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 ? 0 : 1).CompareTo(b.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 ? 0 : 1);
                });
                foreach (var adapter in adapters)
                {
                    if (adapter.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (adapter.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211 && adapter.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Ethernet) continue;
                    var name = (adapter.Name + " " + adapter.Description).ToLowerInvariant();
                    bool virtualAdapter = false;
                    foreach (var word in new[] { "vmware", "virtual", "hyper-v", "mihomo", "tailscale", "docker", "wsl", "loopback" }) if (name.Contains(word)) virtualAdapter = true;
                    if (virtualAdapter) continue;
                    var properties = adapter.GetIPProperties();
                    bool gateway = false;
                    foreach (var address in properties.GatewayAddresses) if (IsPrivateV4(address.Address)) gateway = true;
                    if (!gateway) continue;
                    foreach (var address in properties.UnicastAddresses) if (IsPrivateV4(address.Address)) return address.Address.ToString();
                }
            }
            catch { }
            return null;
        }
        internal static bool IsPrivateV4(System.Net.IPAddress address)
        {
            if (address == null || address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) return false;
            var bytes = address.GetAddressBytes();
            return bytes[3] > 0 && bytes[3] < 255 && (bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) || (bytes[0] == 192 && bytes[1] == 168));
        }
        internal static void OpenWebLink(string value)
        {
            Uri uri;
            if (!Uri.TryCreate(value, UriKind.Absolute, out uri) || (uri.Scheme != "https" && uri.Scheme != "http") || !String.IsNullOrEmpty(uri.UserInfo)) return;
            try { Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true }); }
            catch (Exception error) { HostLog.Write("external-link", error.GetType().Name); }
        }
    }

    internal static class HostLog
    {
        private static readonly object Gate = new object();
        internal static string LogDirectory { get { return Path.Combine(Program.LocalRoot, "logs"); } }
        internal static void Write(string source, object value)
        {
            try
            {
                lock (Gate)
                {
                    Directory.CreateDirectory(LogDirectory);
                    var path = Path.Combine(LogDirectory, "desktop.log");
                    if (File.Exists(path) && new FileInfo(path).Length > 5 * 1024 * 1024)
                    {
                        var old = Path.Combine(LogDirectory, "desktop.previous.log");
                        if (File.Exists(old)) File.Delete(old);
                        File.Move(path, old);
                    }
                    File.AppendAllText(path, DateTimeOffset.Now.ToString("o") + " [" + source + "] " + Convert.ToString(value) + Environment.NewLine);
                }
            }
            catch { }
        }
    }

    internal static class Native
    {
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] internal static extern uint RegisterWindowMessage(string value);
        [DllImport("user32.dll")] internal static extern bool PostMessage(IntPtr target, uint message, IntPtr wParam, IntPtr lParam);
        [DllImport("user32.dll")] internal static extern bool SetForegroundWindow(IntPtr hwnd);
        [DllImport("user32.dll")] internal static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] internal static extern bool SetProcessDpiAwarenessContext(IntPtr value);
        [DllImport("dwmapi.dll")] internal static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    }
    internal sealed class OutdatedServerException : IOException
    {
        internal OutdatedServerException() : base("The existing DreamShield service does not provide the current console resources; no process was stopped or replaced.") { }
    }
}
