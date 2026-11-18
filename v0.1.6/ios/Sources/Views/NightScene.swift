import SwiftUI

/// A local visual preference. Choosing a scene never changes recording or AI.
enum NightAtmosphere: String, CaseIterable, Identifiable {
    case window, dusk, rain
    var id: String { rawValue }

    var title: String {
        switch self {
        case .window: return "夜窗"
        case .dusk: return "暮色"
        case .rain: return "雨幕"
        }
    }

    var symbol: String {
        switch self {
        case .window: return "sparkles"
        case .dusk: return "sun.haze"
        case .rain: return "cloud.rain"
        }
    }

    fileprivate var sky: [Color] {
        switch self {
        case .window: return [Color(red: 0.12, green: 0.16, blue: 0.36), Color(red: 0.42, green: 0.40, blue: 0.66)]
        case .dusk: return [Color(red: 0.34, green: 0.26, blue: 0.49), Color(red: 0.86, green: 0.56, blue: 0.56)]
        case .rain: return [Color(red: 0.15, green: 0.26, blue: 0.36), Color(red: 0.39, green: 0.57, blue: 0.63)]
        }
    }

    fileprivate var blanket: Color {
        switch self {
        case .window: return Color(red: 0.59, green: 0.55, blue: 0.83)
        case .dusk: return Color(red: 0.76, green: 0.49, blue: 0.59)
        case .rain: return Color(red: 0.40, green: 0.66, blue: 0.66)
        }
    }
}

/// Original code-drawn bedroom illustration: a quiet window, warm lamp and a
/// folded-blanket companion. It is decorative, never a camera or device state.
struct NightSceneArtwork: View {
    let atmosphere: NightAtmosphere

    var body: some View {
        Canvas { context, size in
            var drawing = context
            drawing.scaleBy(x: size.width / 640, y: size.height / 380)
            drawRoom(in: &drawing)
        }
        .aspectRatio(640.0 / 380.0, contentMode: .fit)
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private func drawRoom(in context: inout GraphicsContext) {
        let wall = Color(red: 0.19, green: 0.18, blue: 0.34)
        let frame = Color(red: 0.64, green: 0.61, blue: 0.78)
        let cream = Color(red: 0.98, green: 0.90, blue: 0.76)

        context.fill(Path(CGRect(x: 0, y: 0, width: 640, height: 380)), with: .linearGradient(
            Gradient(colors: [wall, Color(red: 0.28, green: 0.25, blue: 0.43)]),
            startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 640, y: 380)))
        rect(&context, x: 196, y: 28, width: 271, height: 241, radius: 28, color: frame.opacity(0.16))
        rect(&context, x: 207, y: 37, width: 249, height: 218, radius: 21, color: frame)
        let window = Path(roundedRect: CGRect(x: 217, y: 47, width: 229, height: 198), cornerRadius: 14)
        context.fill(window, with: .linearGradient(Gradient(colors: atmosphere.sky), startPoint: CGPoint(x: 330, y: 47), endPoint: CGPoint(x: 330, y: 245)))

        // Fixed stars are deliberately still: no perpetual animation or timer.
        let stars: [(CGFloat, CGFloat, CGFloat)] = [(242, 75, 2), (289, 62, 1.4), (399, 86, 2), (419, 61, 1.3), (369, 122, 1.4), (271, 138, 1.5), (310, 101, 1)]
        if atmosphere != .rain {
            for (x, y, radius) in stars {
                ellipse(&context, x: x, y: y, width: radius * 2, height: radius * 2, color: cream.opacity(0.8))
            }
            var sparkle = Path()
            sparkle.move(to: CGPoint(x: 383, y: 62)); sparkle.addLine(to: CGPoint(x: 383, y: 74))
            sparkle.move(to: CGPoint(x: 377, y: 68)); sparkle.addLine(to: CGPoint(x: 389, y: 68))
            context.stroke(sparkle, with: .color(cream), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
        } else {
            for column in 0..<7 {
                for row in 0..<4 {
                    let x = CGFloat(234 + column * 30)
                    let y = CGFloat(63 + row * 40 + (column % 2) * 10)
                    var drop = Path()
                    drop.move(to: CGPoint(x: x, y: y)); drop.addLine(to: CGPoint(x: x - 6, y: y + 14))
                    context.stroke(drop, with: .color(.white.opacity(0.20)), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                }
            }
        }

        var hills = Path()
        hills.move(to: CGPoint(x: 217, y: 220))
        hills.addCurve(to: CGPoint(x: 446, y: 219), control1: CGPoint(x: 299, y: 150), control2: CGPoint(x: 357, y: 254))
        hills.addLine(to: CGPoint(x: 446, y: 245)); hills.addLine(to: CGPoint(x: 217, y: 245)); hills.closeSubpath()
        context.fill(hills, with: .color(Color(red: 0.28, green: 0.29, blue: 0.50)))
        rect(&context, x: 326, y: 47, width: 8, height: 198, radius: 0, color: frame)
        rect(&context, x: 217, y: 143, width: 229, height: 7, radius: 0, color: frame)
        rect(&context, x: 197, y: 249, width: 269, height: 11, radius: 5, color: Color(red: 0.76, green: 0.71, blue: 0.83))

        // Curtains frame the window without obscuring its warm, readable shape.
        var curtain = Path()
        curtain.move(to: CGPoint(x: 169, y: 22)); curtain.addLine(to: CGPoint(x: 221, y: 22))
        curtain.addQuadCurve(to: CGPoint(x: 193, y: 217), control: CGPoint(x: 209, y: 157))
        curtain.addLine(to: CGPoint(x: 202, y: 287)); curtain.addLine(to: CGPoint(x: 161, y: 287)); curtain.closeSubpath()
        context.fill(curtain, with: .color(Color(red: 0.45, green: 0.40, blue: 0.60)))
        rect(&context, x: 178, y: 30, width: 3, height: 247, radius: 1, color: .white.opacity(0.08))

        // Floor and bed layers give depth while keeping the artwork lightweight.
        context.fill(Path(CGRect(x: 0, y: 316, width: 640, height: 64)), with: .color(Color(red: 0.16, green: 0.16, blue: 0.28)))
        ellipse(&context, x: 148, y: 322, width: 372, height: 39, color: .black.opacity(0.16))
        rect(&context, x: 196, y: 280, width: 335, height: 58, radius: 21, color: Color(red: 0.48, green: 0.43, blue: 0.64))
        rect(&context, x: 198, y: 274, width: 329, height: 43, radius: 20, color: Color(red: 0.77, green: 0.71, blue: 0.83))
        rect(&context, x: 422, y: 258, width: 87, height: 29, radius: 14, color: cream)
        rect(&context, x: 210, y: 330, width: 13, height: 18, radius: 4, color: frame)
        rect(&context, x: 497, y: 330, width: 13, height: 18, radius: 4, color: frame)

        // The companion is a folded blanket with stitched, sleeping eyes.
        var blanket = Path()
        blanket.move(to: CGPoint(x: 278, y: 280))
        blanket.addCurve(to: CGPoint(x: 297, y: 186), control1: CGPoint(x: 266, y: 249), control2: CGPoint(x: 271, y: 197))
        blanket.addCurve(to: CGPoint(x: 367, y: 191), control1: CGPoint(x: 318, y: 174), control2: CGPoint(x: 352, y: 177))
        blanket.addCurve(to: CGPoint(x: 392, y: 279), control1: CGPoint(x: 389, y: 208), control2: CGPoint(x: 379, y: 249))
        blanket.addQuadCurve(to: CGPoint(x: 366, y: 290), control: CGPoint(x: 388, y: 299))
        blanket.addQuadCurve(to: CGPoint(x: 339, y: 290), control: CGPoint(x: 352, y: 303))
        blanket.addQuadCurve(to: CGPoint(x: 311, y: 290), control: CGPoint(x: 325, y: 302))
        blanket.addQuadCurve(to: CGPoint(x: 278, y: 280), control: CGPoint(x: 282, y: 299)); blanket.closeSubpath()
        context.fill(blanket, with: .linearGradient(Gradient(colors: [Color(red: 0.93, green: 0.87, blue: 0.97), atmosphere.blanket]), startPoint: CGPoint(x: 311, y: 183), endPoint: CGPoint(x: 367, y: 292)))
        var fold = Path()
        fold.move(to: CGPoint(x: 298, y: 193)); fold.addQuadCurve(to: CGPoint(x: 370, y: 206), control: CGPoint(x: 340, y: 180))
        context.stroke(fold, with: .color(.white.opacity(0.44)), style: StrokeStyle(lineWidth: 5, lineCap: .round))
        for x in [CGFloat(310), CGFloat(348)] {
            var eye = Path()
            eye.move(to: CGPoint(x: x, y: 225)); eye.addQuadCurve(to: CGPoint(x: x + 13, y: 225), control: CGPoint(x: x + 6.5, y: 233))
            context.stroke(eye, with: .color(wall), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
        ellipse(&context, x: 303, y: 238, width: 13, height: 6, color: .pink.opacity(0.24))
        ellipse(&context, x: 356, y: 238, width: 13, height: 6, color: .pink.opacity(0.24))
        var seam = Path()
        seam.move(to: CGPoint(x: 379, y: 253)); seam.addQuadCurve(to: CGPoint(x: 371, y: 282), control: CGPoint(x: 367, y: 261))
        context.stroke(seam, with: .color(.white.opacity(0.5)), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [3, 4]))

        // Bedside lamp, with a contained halo instead of animated flashing.
        ellipse(&context, x: 55, y: 136, width: 138, height: 170, color: cream.opacity(0.055))
        rect(&context, x: 77, y: 278, width: 86, height: 12, radius: 5, color: frame)
        rect(&context, x: 84, y: 288, width: 7, height: 47, radius: 2, color: frame.opacity(0.8))
        rect(&context, x: 148, y: 288, width: 7, height: 47, radius: 2, color: frame.opacity(0.8))
        rect(&context, x: 117, y: 229, width: 5, height: 49, radius: 2, color: cream)
        ellipse(&context, x: 103, y: 270, width: 34, height: 7, color: cream)
        var shade = Path()
        shade.move(to: CGPoint(x: 102, y: 197)); shade.addLine(to: CGPoint(x: 137, y: 197)); shade.addLine(to: CGPoint(x: 150, y: 233)); shade.addQuadCurve(to: CGPoint(x: 89, y: 233), control: CGPoint(x: 119, y: 245)); shade.closeSubpath()
        context.fill(shade, with: .color(cream))

        // A small plant anchors the opposite side of the room.
        rect(&context, x: 563, y: 284, width: 33, height: 39, radius: 9, color: Color(red: 0.74, green: 0.57, blue: 0.57))
        var stem = Path()
        stem.move(to: CGPoint(x: 579, y: 287)); stem.addQuadCurve(to: CGPoint(x: 581, y: 233), control: CGPoint(x: 573, y: 251))
        context.stroke(stem, with: .color(Color(red: 0.49, green: 0.70, blue: 0.63)), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        ellipse(&context, x: 554, y: 248, width: 23, height: 12, color: Color(red: 0.49, green: 0.70, blue: 0.63))
        ellipse(&context, x: 579, y: 233, width: 25, height: 13, color: Color(red: 0.59, green: 0.76, blue: 0.68))
        ellipse(&context, x: 579, y: 263, width: 20, height: 11, color: Color(red: 0.49, green: 0.70, blue: 0.63))
    }

    private func rect(_ context: inout GraphicsContext, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat, radius: CGFloat, color: Color) {
        context.fill(Path(roundedRect: CGRect(x: x, y: y, width: width, height: height), cornerRadius: radius), with: .color(color))
    }

    private func ellipse(_ context: inout GraphicsContext, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat, color: Color) {
        context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: width, height: height)), with: .color(color))
    }
}
