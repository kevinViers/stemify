import QtQuick
import QtQuick.Shapes

// Static dashed rounded border (geometry built once; only opacity animates).
Shape {
    id: s
    property color color: Theme.accentGlow
    property real radius: 12
    property real lineWidth: 2
    preferredRendererType: Shape.CurveRenderer
    ShapePath {
        strokeColor: s.color
        strokeWidth: s.lineWidth
        strokeStyle: ShapePath.DashLine
        dashPattern: [5, 4]
        fillColor: "transparent"
        PathRectangle {
            x: s.lineWidth / 2
            y: s.lineWidth / 2
            width: s.width - s.lineWidth
            height: s.height - s.lineWidth
            radius: s.radius
        }
    }
}
