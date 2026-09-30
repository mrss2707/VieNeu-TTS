import AVFoundation
import UIKit

/// UI demo A/B: FP16·8 steps vs INT8·12 steps (cùng comfort DSP, giọng Mỹ Duyên).
/// So sánh chất lượng + build size (graph on disk) + RAM (phys_footprint).
final class ViewController: UIViewController {
    private enum Mode: Int {
        case fp16 = 0
        case int8 = 1

        var title: String {
            switch self {
            case .fp16: return "FP16 · 8 steps"
            case .int8: return "INT8 · 12 steps"
            }
        }

        var graphDir: String {
            switch self {
            case .fp16: return "nano_graphs"
            case .int8: return "nano_graphs_int8"
            }
        }

        var steps: Int {
            switch self {
            case .fp16: return 8
            case .int8: return 12
            }
        }
    }

    private let paragraphLabel = UILabel()
    private let textView = UITextView()
    private let btnFp16 = UIButton(type: .system)
    private let btnInt8 = UIButton(type: .system)
    private let statusLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)

    private var engines: [Mode: NanoTtsEngine] = [:]
    private var player: AVAudioPlayer?
    private var busy = false

    private let demoParagraph = """
    Năm mười lăm tuổi, Tiêu Viêm từng là thiên tài trẻ nhất của gia tộc Tiêu. \
    Thế nhưng, chỉ sau một đêm, đấu khí trong cơ thể hắn đột nhiên biến mất không dấu vết. \
    Bốn năm sau, thiếu niên năm nào đứng giữa quảng trường, mặc cho ánh mắt chế giễu của tộc nhân, \
    hắn siết chặt nắm đấm — nhất định phải lấy lại tất cả những gì đã mất.
    """

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "Supermeo Mobile TTS"
        buildUI()
        preload()
    }

    private func buildUI() {
        paragraphLabel.text = "Đấu phá · Mỹ Duyên · comfort DSP"
        paragraphLabel.font = .preferredFont(forTextStyle: .headline)

        textView.text = demoParagraph
        textView.isEditable = false
        textView.font = .preferredFont(forTextStyle: .body)
        textView.layer.borderColor = UIColor.separator.cgColor
        textView.layer.borderWidth = 1
        textView.layer.cornerRadius = 8
        textView.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)

        configure(btnFp16, mode: .fp16)
        configure(btnInt8, mode: .int8)

        statusLabel.text = "Đang nạp graph…"
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center

        spinner.hidesWhenStopped = true

        let stack = UIStackView(arrangedSubviews: [
            paragraphLabel, textView, btnFp16, btnInt8, spinner, statusLabel,
        ])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            textView.heightAnchor.constraint(greaterThanOrEqualToConstant: 160),
        ])
    }

    private func configure(_ button: UIButton, mode: Mode) {
        button.setTitle("Đọc — \(mode.title)", for: .normal)
        button.titleLabel?.font = .preferredFont(forTextStyle: .title2)
        button.tag = mode.rawValue
        button.addTarget(self, action: #selector(onSpeak(_:)), for: .touchUpInside)
    }

    private func preload() {
        setButtonsEnabled(false)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var lines: [String] = []
            var loaded: [Mode: NanoTtsEngine] = [:]
            for mode in [Mode.fp16, Mode.int8] {
                let onDisk = NanoTtsEngine.graphDirBytes(onDisk: .main, name: mode.graphDir)
                do {
                    let eng = try NanoTtsEngine(
                        bundle: .main, graphDirName: mode.graphDir, steps: mode.steps)
                    loaded[mode] = eng
                    lines.append(String(
                        format: "%@ · graph %.0f MB · steps %d",
                        mode.title, onDisk / (1024 * 1024), mode.steps
                    ))
                } catch {
                    lines.append("\(mode.title): LỖI \(error.localizedDescription)")
                }
            }
            let ram = NanoTtsEngine.currentPhysFootprintMb()
            DispatchQueue.main.async {
                guard let self else { return }
                self.engines = loaded
                self.setButtonsEnabled(!loaded.isEmpty)
                self.statusLabel.text = "Sẵn sàng · Mỹ Duyên · comfort ON\n"
                    + lines.joined(separator: "\n")
                    + String(format: "\nRAM sau khi nạp: %.0f MB", ram)
            }
        }
    }

    private func setButtonsEnabled(_ enabled: Bool) {
        btnFp16.isEnabled = enabled
        btnInt8.isEnabled = enabled
    }

    @objc private func onSpeak(_ sender: UIButton) {
        guard let mode = Mode(rawValue: sender.tag),
              let engine = engines[mode], !busy else { return }
        busy = true
        setButtonsEnabled(false)
        spinner.startAnimating()
        statusLabel.text = "Đang tổng hợp (\(mode.title))…"

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let result = try engine.synthesizeDemo()
                let wavURL = try engine.writeWav(from: result)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.busy = false
                    self.spinner.stopAnimating()
                    self.setButtonsEnabled(true)
                    let rtf = (result.generationMs / 1000.0) / max(result.audioSeconds, 0.001)
                    self.statusLabel.text = String(
                        format: """
                        OK %@ · comfort ON — %.1fs · %.0f ms sinh · %d chunk · RTF %.2f%@ \
                        · graph %.0f MB · RAM %.0f MB
                        """,
                        mode.title,
                        result.audioSeconds,
                        result.generationMs,
                        result.nChunks,
                        rtf,
                        result.hasNan ? " · CÓ NaN!" : "",
                        result.graphBytesOnDisk / (1024 * 1024),
                        result.physFootprintMb
                    )
                    do {
                        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                        try AVAudioSession.sharedInstance().setActive(true)
                        self.player = try AVAudioPlayer(contentsOf: wavURL)
                        self.player?.play()
                    } catch {
                        self.statusLabel.text = "Sinh OK nhưng phát lỗi: \(error.localizedDescription)"
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.busy = false
                    self.spinner.stopAnimating()
                    self.setButtonsEnabled(true)
                    self.statusLabel.text = "Lỗi: \(error.localizedDescription)"
                }
            }
        }
    }
}
