import SwiftUI
import UIKit

enum HowToAssets {
    static let language: String =
        (Bundle.main.preferredLocalizations.first ?? "en").lowercased().hasPrefix("ru") ? "ru" : "en"

    static func image(prefix: String, step: Int) -> UIImage? {
        let name = "\(prefix)-\(language)-\(step)"
        let url = Bundle.main.url(forResource: name, withExtension: "webp")
            ?? Bundle.main.url(forResource: name, withExtension: "webp", subdirectory: "HowTo")
        guard let url else { return nil }
        return UIImage(contentsOfFile: url.path)
    }
}

private struct HowToStep {
    let title: LocalizedStringKey
    let text: LocalizedStringKey
}

@MainActor
private let howToSteps: [HowToStep] = [
    HowToStep(title: "Open your key",
              text: "On the Chats tab, tap the QR icon at the top left. That opens My key — your public key, which is safe to share."),
    HowToStep(title: "Give the key to your contact",
              text: "The safest way is to show the QR code and let them scan it in person. If they are far away, tap Copy key and send the text however you like. Intercepting this key gains an eavesdropper nothing — it is the public half."),
    HowToStep(title: "Open Add contact",
              text: "Go back to Chats, tap the … menu at the top right and choose Add contact."),
    HowToStep(title: "Add their key",
              text: "Type a name, then paste the key you received or scan their QR code, and tap Add. Your contact does exactly the same on their side."),
]

@MainActor
private let howToSetupSteps: [HowToStep] = [
    HowToStep(title: "Open the keyboard settings",
              text: "In Kryptos open Settings → Keyboard."),
    HowToStep(title: "Turn on auto-decrypt",
              text: "Leave “Auto-decrypt on open” switched on. Then, whenever an encrypted message is on the clipboard, the Kryptos keyboard decrypts it the moment it opens — copy the message in any messenger and it is revealed right there."),
    HowToStep(title: "Open the iOS keyboard settings",
              text: "Open the iOS Settings app, find Kryptos in the list of apps and tap Keyboards. On the same screen you can set “Paste from Other Apps” to Allow, so iOS stops asking every time a copied message is read."),
    HowToStep(title: "Enable the keyboard and Full Access",
              text: "Turn on Kryptos, then turn on Allow Full Access — without it the keyboard cannot reach your keys and encryption in it will not work. iOS shows a general warning about third-party keyboards here; Kryptos never opens a network connection, so what you type cannot leave the device."),
]

@MainActor
private let howToTopics: [HowToStep] = [
    HowToStep(title: "Sending and receiving",
              text: "Open the contact, type a message and send it: Kryptos encrypts it and puts the result on the clipboard straight away, so you only have to paste it into the messenger. When a reply arrives, copy it and come back to Kryptos — the app recognises its own ciphertext on the clipboard and shows the decrypted text by itself."),
    HowToStep(title: "The Kryptos keyboard",
              text: "Once the keyboard is set up (see “Kryptos in other apps”), you can encrypt without leaving the other app. Type your text, tap the lock, and only the ciphertext is left in the field. A copied incoming message is decrypted by the keyboard itself and shown in a panel above the keys — the plaintext never reaches the messenger."),
    HowToStep(title: "Password mode",
              text: "If exchanging keys is too much trouble, agree on one shared password instead. On the Password tab enter the password and the text, and you get a block that is unlocked with the same password. Simpler, but it is exactly as strong as the password you chose and as safe as the way you passed it on."),
    HowToStep(title: "Hiding a message in a photo",
              text: "On the Photo tab a message is hidden inside an ordinary picture: pick a photo, set a password and type the text. Send the result as a file (a document) — sent as a normal photo it gets recompressed by the messenger and the hidden data is destroyed."),
    HowToStep(title: "PGP",
              text: "The PGP tab is for writing to people who already use ordinary OpenPGP. It keeps its own keys and its own list of recipients, separate from your chats."),
]

@MainActor
private let howToNotes: [LocalizedStringKey] = [
    "The very first message to a new contact is noticeably longer than the rest: it carries the post-quantum handshake. Once they reply, messages become short.",
    "The same message decrypts only once — that is forward secrecy at work. Messages you have already read stay in the chat history.",
    "Your keys live on this device only. Before you change phones, make a key backup in Settings.",
    "The app is completely offline: no servers, no accounts, and it never opens a network connection.",
]

private struct HowToWalkthrough: View {
    let title: LocalizedStringKey
    let intro: LocalizedStringKey
    let steps: [HowToStep]
    let assetPrefix: String

    @State private var shots: [Int: UIImage] = [:]

    var body: some View {
        List {
            Section {
                Text(intro).font(.callout).foregroundStyle(.secondary)
            }

            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                Section {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(step.title).font(.headline).foregroundStyle(.primary)
                        Text(step.text).font(.callout).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 3)

                    if let shot = shots[index + 1] {
                        Image(uiImage: shot)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 340)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(KTheme.hairline, lineWidth: 1))
                            .shadow(color: .black.opacity(0.14), radius: 10, y: 4)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 16, trailing: 16))
                            .accessibilityHidden(true)
                    }
                } header: {
                    Text("Step \(index + 1)")
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard shots.isEmpty else { return }
            var loaded: [Int: UIImage] = [:]
            for step in 1 ... steps.count {
                if let image = HowToAssets.image(prefix: assetPrefix, step: step) { loaded[step] = image }
            }
            shots = loaded
        }
    }
}

struct HowToView: View {
    var body: some View {
        List {
            Section {
                Text("Kryptos encrypts a message right on your device. You send the resulting ciphertext as ordinary text — in any messenger, by SMS or by email. Whatever carries it sees only a block of characters, and only the person holding the right key can read it. The app never needs an internet connection.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            Section {
                NavigationLink {
                    HowToWalkthrough(title: "Key exchange",
                                     intro: "Everything below is done once, when you add a new contact — after that you simply write to each other.",
                                     steps: howToSteps,
                                     assetPrefix: "howto")
                } label: {
                    Text("Key exchange")
                }
                NavigationLink {
                    HowToWalkthrough(title: "Kryptos in other apps",
                                     intro: "The Kryptos keyboard lets you encrypt and decrypt without leaving the app you are in. It is set up once: turn on auto-decrypt inside Kryptos, then enable the keyboard in the iOS settings.",
                                     steps: howToSetupSteps,
                                     assetPrefix: "howto-setup")
                } label: {
                    Text("Kryptos in other apps")
                }
            }

            ForEach(Array(howToTopics.enumerated()), id: \.offset) { _, topic in
                Section {
                    Text(topic.text).font(.callout).foregroundStyle(.secondary)
                } header: {
                    Text(topic.title)
                }
            }

            Section {
                ForEach(Array(howToNotes.enumerated()), id: \.offset) { _, note in
                    Label { Text(note).font(.callout).foregroundStyle(.secondary) } icon: {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(KTheme.accent)
                    }
                }
            } header: {
                Text("Good to know")
            }
        }
        .navigationTitle("How to use")
        .navigationBarTitleDisplayMode(.inline)
    }
}

@MainActor
private let faqItems: [HowToStep] = [
    HowToStep(title: "Does my contact need Kryptos too?",
              text: "Yes — nothing but Kryptos can decrypt the message. Which phone each of you carries does not matter though: the iPhone and Android versions are fully compatible, and keys and messages work in both directions."),
    HowToStep(title: "Does it need an internet connection?",
              text: "No. Kryptos has no servers and no accounts, and it never opens a network connection. Encryption happens entirely on the device, and delivery is handled by whatever messenger you already use."),
    HowToStep(title: "Can the messenger read my messages?",
              text: "No — it only ever gets text that is already encrypted. It does still see that you sent something, to whom, and roughly how much. That is metadata, and Kryptos does not hide it; you can at least mask the length in Settings → Privacy."),
    HowToStep(title: "Can I send a photo or a file through Kryptos?",
              text: "No, Kryptos encrypts text only. The Photo tab does something different: it hides a text message inside a picture, rather than sending the picture itself securely."),
    HowToStep(title: "Why is the first message so long?",
              text: "It carries the post-quantum handshake (PQXDH with Kyber), which is about two kilobytes. It is sent once; as soon as your contact replies, messages become many times shorter."),
    HowToStep(title: "What does “Mask message length” do?",
              text: "The length of the ciphertext normally follows the length of your text, so it still shows whether you answered in one word or wrote a page. With this on, Kryptos pads every message with random bytes up to the next size on a fixed ladder — 64 bytes, 128, 256 and so on — so texts of different lengths come out the same size. It covers Chats and Password mode, including messages hidden in ordinary text; photos and PGP are not affected. Your contact strips the padding automatically, whatever their own setting is. The price is a longer message, sometimes close to twice as long, which is why it is off by default."),
    HowToStep(title: "What is “Message field in the keyboard” for?",
              text: "Normally you type into the messenger's own input field, so it sees every letter before anything is encrypted — and most messengers save what you typed as a draft. Switch this field on and you type inside the Kryptos keyboard instead: the messenger receives nothing at all until you tap the lock, and then it gets only the finished ciphertext. What you are typing stays inside the keyboard, is never written to storage, and disappears with the rest of your data when you erase it. The separate “Field button on the keyboard” setting puts a button next to the lock so you can turn the field on without opening Settings."),
    HowToStep(title: "Why will a message not decrypt a second time?",
              text: "That is not a fault but forward secrecy: the key for each message is destroyed the moment it is read, so old correspondence cannot be recovered even by someone who takes your phone. Messages you have already opened remain in the chat history."),
    HowToStep(title: "It says it could not decrypt. What now?",
              text: "Usually the wrong contact or the wrong profile is selected. If every new message from one person fails, the session has gone out of sync: send them your key again and have them add you a second time."),
    HowToStep(title: "What do disappearing messages actually do?",
              text: "They erase the conversation inside Kryptos after the time you pick — on your device, and on your contact's if they set the same thing themselves. What they cannot touch is the ciphertext already sitting in the messenger: only the messenger itself can remove that."),
    HowToStep(title: "What is the safety number for?",
              text: "It is a fingerprint of the key. Compare it with your contact over some other channel — read it out loud, for instance. If the numbers match, there is definitely nobody in the middle."),
    HowToStep(title: "Can I use one key on two phones?",
              text: "No. A conversation runs from one device: its key chain cannot advance in two places at once. Move your keys to the new phone with a key backup and then use only that phone."),
    HowToStep(title: "What happens if I delete the app or lose my phone?",
              text: "The keys live on the device and nowhere else, so deleting the app destroys them for good and there is nothing left to decrypt with. The only insurance is to make a key backup in Settings beforehand. The flip side of the same property: whoever finds the phone gets nothing either."),
    HowToStep(title: "Does the key backup restore my messages too?",
              text: "No, and that is deliberate. The file carries only your keys, your contacts and your PGP keys — message history is never written into it. After restoring on a new phone you carry on with the same people, but the old messages do not come back."),
    HowToStep(title: "I hid a message in a photo and it will not open",
              text: "The photo has to be sent as a file (a document). Sent as an ordinary picture it is recompressed by the messenger, and recompression destroys the hidden data."),
    HowToStep(title: "Why does the app ask for the camera?",
              text: "Only to scan a contact key from a QR code. The camera is used nowhere else, and refusing access breaks nothing — you can always paste the key as text instead."),
    HowToStep(title: "Do my keys and messages end up in the phone's cloud backup?",
              text: "No. The keys sit in the Keychain marked as this-device-only, so they are not saved into an iCloud or computer backup. Everything else is encrypted with exactly those keys, so even if a copy of it were backed up it would be unreadable anywhere else."),
    HowToStep(title: "What is the panic password?",
              text: "It is a second password for the lock screen. Type it instead of your normal passcode and the app silently and irreversibly destroys every key, message and setting, then opens as if it had just been installed. You set it up in Settings → Privacy."),
    HowToStep(title: "I forgot the password to my key backup",
              text: "There is no way to recover it. The file is encrypted with that password and Kryptos keeps no copy of it anywhere. Make a fresh backup with a password you will not forget."),
    HowToStep(title: "What are profiles for?",
              text: "A profile is a separate keypair with its own contacts. You can keep several and switch between them — to keep work and private life apart, for example."),
    HowToStep(title: "How strong is the encryption?",
              text: "Chats use Signal's own libsignal library — the same protocol as Signal itself, post-quantum handshake included. Password mode and photos use Argon2id and AES-256-GCM. Kryptos contains no home-made cryptography."),
]

struct FAQView: View {
    var body: some View {
        List {
            ForEach(Array(faqItems.enumerated()), id: \.offset) { _, item in
                DisclosureGroup {
                    Text(item.text)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 4)
                } label: {
                    Text(item.title)
                        .font(.callout.weight(.medium))
                        .foregroundStyle(.primary)
                }
            }
        }
        .navigationTitle("Questions and answers")
        .navigationBarTitleDisplayMode(.inline)
    }
}
