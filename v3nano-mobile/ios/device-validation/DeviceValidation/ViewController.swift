import AVFoundation
import UIKit

/// UI demo A/B: INT8·12 steps comfort DSP — noise gate vs spectral denoise.
/// 1) Chỉ comfort (EQ + soft-clip)
/// 2) + noise gate (khoảng trống)
/// 3) + gate + spectral STFT (nhiễu TRONG lúc nói) ← production path đề xuất
final class ViewController: UIViewController {
    private enum Mode: Int {
        case comfortOnly = 0
        case gate = 1
        case gateSpectral = 2

        var title: String {
            switch self {
            case .comfortOnly: return "A/B — chỉ comfort"
            case .gate: return "A/B — chỉ noise gate"
            case .gateSpectral: return "★ chốt — gate + spectral"
            }
        }

        var noiseFloor: Bool { self != .comfortOnly }
        var spectral: Bool { self == .gateSpectral }
    }

    private let paragraphLabel = UILabel()
    private let textView = UITextView()
    private let buttons: [UIButton] = (0..<3).map { _ in UIButton(type: .system) }
    private let statusLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)

    private var engine: NanoTtsEngine?
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
        paragraphLabel.text = "Đấu phá · Mỹ Duyên · INT8 12 steps · A/B nhiễu"
        paragraphLabel.font = .preferredFont(forTextStyle: .headline)

        textView.text = demoParagraph
        textView.isEditable = false
        textView.font = .preferredFont(forTextStyle: .body)
        textView.layer.borderColor = UIColor.separator.cgColor
        textView.layer.borderWidth = 1
        textView.layer.cornerRadius = 8
        textView.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)

        // ★ chốt lên trước, A/B sau
        let order: [Mode] = [.gateSpectral, .gate, .comfortOnly]
        for (slot, mode) in order.enumerated() {
            let button = buttons[slot]
            button.setTitle("Đọc — \(mode.title)", for: .normal)
            button.titleLabel?.font = .preferredFont(forTextStyle: .title2)
            button.tag = mode.rawValue
            button.addTarget(self, action: #selector(onSpeak(_:)), for: .touchUpInside)
        }

        statusLabel.text = "Đang nạp graph INT8…"
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center

        spinner.hidesWhenStopped = true

        let stack = UIStackView(arrangedSubviews: [paragraphLabel, textView] + buttons + [spinner, statusLabel])
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            textView.heightAnchor.constraint(greaterThanOrEqualToConstant: 140),
        ])
    }

    private func preload() {
        setButtonsEnabled(false)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let onDisk = NanoTtsEngine.graphDirBytes(onDisk: .main, name: "nano_graphs_int8")
            var message = ""
            var eng: NanoTtsEngine?
            do {
                eng = try NanoTtsEngine(
                    bundle: .main, graphDirName: "nano_graphs_int8", steps: 12)
                message = String(
                    format: "Chốt (khớp Android): INT8 · 12 · gate + spectral · graph %.0f MB · Mỹ Duyên",
                    onDisk / (1024 * 1024)
                )
            } catch {
                message = "Lỗi nạp model: \(error.localizedDescription)"
            }
            let ram = NanoTtsEngine.currentPhysFootprintMb()
            DispatchQueue.main.async {
                guard let self else { return }
                self.engine = eng
                self.setButtonsEnabled(eng != nil)
                self.statusLabel.text = message + String(format: "\nRAM: %.0f MB", ram)
            }
        }
    }

    private func setButtonsEnabled(_ enabled: Bool) {
        for b in buttons { b.isEnabled = enabled }
    }

    @objc private func onSpeak(_ sender: UIButton) {
        guard let mode = Mode(rawValue: sender.tag),
              let engine, !busy else { return }
        busy = true
        setButtonsEnabled(false)
        spinner.startAnimating()
        statusLabel.text = "Đang tổng hợp (\(mode.title))…"

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let result = try engine.synthesizeDemo(
                    withNoiseFloor: mode.noiseFloor,
                    spectralDenoise: mode.spectral
                )
                let wavURL = try engine.writeWav(from: result)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.busy = false
                    self.spinner.stopAnimating()
                    self.setButtonsEnabled(true)
                    let rtf = (result.generationMs / 1000.0) / max(result.audioSeconds, 0.001)
                    self.statusLabel.text = String(
                        format: "OK %@ — %.1fs · %.0f ms · RTF %.2f · NF %@ · spec %@ · RAM %.0f MB%@",
                        mode.title,
                        result.audioSeconds,
                        result.generationMs,
                        rtf,
                        mode.noiseFloor ? "ON" : "OFF",
                        mode.spectral ? "ON" : "OFF",
                        result.physFootprintMb,
                        result.hasNan ? " · CÓ NaN!" : ""
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
