import AVFoundation
import UIKit

/// UI demo A/B: INT8·12 steps comfort DSP **có / không noise floor** (giọng Mỹ Duyên).
/// Cùng EQ + soft-clip; chỉ toggle downward expander −48 dBFS (chống “lạo xạo” nền).
final class ViewController: UIViewController {
    private enum Mode: Int {
        case noNoiseFloor = 0
        case withNoiseFloor = 1

        var title: String {
            switch self {
            case .noNoiseFloor: return "INT8 · 12 · không NF"
            case .withNoiseFloor: return "INT8 · 12 · + noise floor"
            }
        }

        var graphDir: String { "nano_graphs_int8" }
        var steps: Int { 12 }
        var noiseFloor: Bool { self == .withNoiseFloor }
    }

    private let paragraphLabel = UILabel()
    private let textView = UITextView()
    private let btnOff = UIButton(type: .system)
    private let btnOn = UIButton(type: .system)
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
        paragraphLabel.text = "Đấu phá · Mỹ Duyên · INT8 12 steps · comfort DSP"
        paragraphLabel.font = .preferredFont(forTextStyle: .headline)

        textView.text = demoParagraph
        textView.isEditable = false
        textView.font = .preferredFont(forTextStyle: .body)
        textView.layer.borderColor = UIColor.separator.cgColor
        textView.layer.borderWidth = 1
        textView.layer.cornerRadius = 8
        textView.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)

        configure(btnOff, mode: .noNoiseFloor)
        configure(btnOn, mode: .withNoiseFloor)

        statusLabel.text = "Đang nạp graph INT8…"
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center

        spinner.hidesWhenStopped = true

        let stack = UIStackView(arrangedSubviews: [
            paragraphLabel, textView, btnOff, btnOn, spinner, statusLabel,
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
            let onDisk = NanoTtsEngine.graphDirBytes(onDisk: .main, name: "nano_graphs_int8")
            var message = ""
            var eng: NanoTtsEngine?
            do {
                eng = try NanoTtsEngine(
                    bundle: .main, graphDirName: "nano_graphs_int8", steps: 12)
                message = String(
                    format: "Sẵn sàng · INT8 · 12 steps · graph %.0f MB · Mỹ Duyên",
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
                self.statusLabel.text = message + String(format: "\nRAM sau khi nạp: %.0f MB", ram)
            }
        }
    }

    private func setButtonsEnabled(_ enabled: Bool) {
        btnOff.isEnabled = enabled
        btnOn.isEnabled = enabled
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
                let result = try engine.synthesizeDemo(withNoiseFloor: mode.noiseFloor)
                let wavURL = try engine.writeWav(from: result)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.busy = false
                    self.spinner.stopAnimating()
                    self.setButtonsEnabled(true)
                    let rtf = (result.generationMs / 1000.0) / max(result.audioSeconds, 0.001)
                    self.statusLabel.text = String(
                        format: """
                        OK %@ — %.1fs · %.0f ms · RTF %.2f · peak-normalized%@ \
                        · NF %@ · RAM %.0f MB
                        """,
                        mode.title,
                        result.audioSeconds,
                        result.generationMs,
                        rtf,
                        result.hasNan ? " · CÓ NaN!" : "",
                        mode.noiseFloor ? "ON" : "OFF",
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
