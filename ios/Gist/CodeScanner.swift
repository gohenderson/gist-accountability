import AVFoundation
import SwiftUI
import VisionKit

/// The camera, reading QR codes and barcodes (VisionKit's live scanner). Calls [onCode]
/// with each code it sees, until the view goes away.
struct CodeScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    /// Can this device scan at all? (Needs a camera and an A12 chip or later.)
    static var supported: Bool { DataScannerViewController.isSupported }

    /// Asks for the camera once; true if it's allowed.
    static func requestCamera() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode()],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        context.coordinator.onCode = onCode
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onCode: (String) -> Void

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem],
                         allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let code) = item, let text = code.payloadStringValue, !text.isEmpty {
                    onCode(text)
                }
            }
        }
    }
}

/// The camera in a box, with a message instead when it can't be used.
struct ScannerPanel: View {
    let onCode: (String) -> Void
    @State private var allowed: Bool?

    var body: some View {
        Group {
            if !CodeScanner.supported {
                message("This device can't scan codes.")
            } else if allowed == false {
                message("Gist can't use the camera. Allow it in Settings → Gist → Camera.")
            } else if allowed == true {
                CodeScanner(onCode: onCode)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 320)
        .background(Color.black.opacity(0.85))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .task {
            if CodeScanner.supported { allowed = await CodeScanner.requestCamera() }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding()
    }
}
