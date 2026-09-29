import AVFoundation
import UIKit

/// UI demo: một đoạn văn + nút bấm để TTS (phone IDs đã export sẵn — chưa có G2P on-device).
final class ViewController: UIViewController {
    private let paragraphLabel = UILabel()
    private let textView = UITextView()
    private let speakButton = UIButton(type: .system)
    private let statusLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)

    private var engine: NanoTtsEngine?
    private var player: AVAudioPlayer?

    private let demoParagraph = """
    Hôm nay trời đẹp. \
    Chúng ta cùng nghe máy đọc một đoạn văn tiếng Việt bằng mô hình VieNeu Nano chạy ngay trên iPhone. \
    Hy vọng giọng đọc đủ tự nhiên để bạn cảm nhận được chất lượng.
    """

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "VieNeu Nano TTS"
        buildUI()
        loadEngine()
    }

    private func buildUI() {
        paragraphLabel.text = "Đoạn văn mẫu"
        paragraphLabel.font = .preferredFont(forTextStyle: .headline)

        textView.text = demoParagraph
        textView.isEditable = false
        textView.font = .preferredFont(forTextStyle: .body)
        textView.layer.borderColor = UIColor.separator.cgColor
        textView.layer.borderWidth = 1
        textView.layer.cornerRadius = 8
        textView.textContainerInset = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 8)

        speakButton.setTitle("Đọc đoạn văn", for: .normal)
        speakButton.titleLabel?.font = .preferredFont(forTextStyle: .title2)
        speakButton.addTarget(self, action: #selector(onSpeak), for: .touchUpInside)

        statusLabel.text = "Đang nạp model…"
        statusLabel.font = .preferredFont(forTextStyle: .footnote)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center

        spinner.hidesWhenStopped = true

        let stack = UIStackView(arrangedSubviews: [paragraphLabel, textView, speakButton, spinner, statusLabel])
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

    private func loadEngine() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var message = "OK"
            var engine: NanoTtsEngine?
            do {
                engine = try NanoTtsEngine(bundle: .main)
            } catch {
                message = error.localizedDescription
            }
            DispatchQueue.main.async {
                guard let self else { return }
                if let engine {
                    self.engine = engine
                    self.speakButton.isEnabled = true
                    self.statusLabel.text = "Sẵn sàng — ấn nút để đọc"
                } else {
                    self.speakButton.isEnabled = false
                    self.statusLabel.text = "Lỗi nạp model: \(message)"
                }
            }
        }
    }

    @objc private func onSpeak() {
        guard let engine else { return }
        speakButton.isEnabled = false
        spinner.startAnimating()
        statusLabel.text = "Đang tổng hợp giọng nói…"

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let result = try engine.synthesizeDemo()
                let wavURL = try engine.writeWav(from: result)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.spinner.stopAnimating()
                    self.speakButton.isEnabled = true
                    self.statusLabel.text = String(
                        format: "OK — %.1fs audio · %.0f ms sinh · %d chunk · RTF %.2f%@",
                        result.audioSeconds,
                        result.generationMs,
                        result.nChunks,
                        (result.generationMs / 1000.0) / max(result.audioSeconds, 0.001),
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
                    self.spinner.stopAnimating()
                    self.speakButton.isEnabled = true
                    self.statusLabel.text = "Lỗi: \(error.localizedDescription)"
                }
            }
        }
    }
}
