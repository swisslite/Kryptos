import Foundation

struct DonationCoin: Identifiable, Sendable {
    let id: String
    let name: String
    let ticker: String
    let address: String

    var grouped: String {
        stride(from: 0, to: address.count, by: 4).map { offset in
            let start = address.index(address.startIndex, offsetBy: offset)
            let end = address.index(start, offsetBy: 4, limitedBy: address.endIndex) ?? address.endIndex
            return String(address[start ..< end])
        }.joined(separator: " ")
    }
}

enum Donations {
    static let coins: [DonationCoin] = [
        DonationCoin(id: "xmr", name: "Monero", ticker: "XMR",
                     address: "86oyPpT7CitPFQTxWdwYwSZ9BUABib37G9AQeeYd2KRcFfwbamaUiZfJYC8gPrfTCiV2X7K4DC1XFi3cfX6N1d1uUL5s3jh"),
        DonationCoin(id: "ton", name: "GRAM (Toncoin)", ticker: "GRAM",
                     address: "UQDrhHMQy8-mZ7pq9KerKAd7QUwjCXjNK-20f0m4yjOkL8jF"),
        DonationCoin(id: "btc", name: "Bitcoin", ticker: "BTC",
                     address: "bc1qwsnex9q5ux88fnt93udn2xmf8752mnx4km2rvm"),
    ]
}
