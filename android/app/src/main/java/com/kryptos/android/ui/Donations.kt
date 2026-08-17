package com.kryptos.android.ui

data class DonationCoin(val id: String, val name: String, val ticker: String, val address: String) {
    val grouped: String = address.chunked(4).joinToString(" ")
}

object Donations {
    val coins = listOf(
        DonationCoin(
            "xmr", "Monero", "XMR",
            "86oyPpT7CitPFQTxWdwYwSZ9BUABib37G9AQeeYd2KRcFfwbamaUiZfJYC8gPrfTCiV2X7K4DC1XFi3cfX6N1d1uUL5s3jh",
        ),
        DonationCoin(
            "ton", "GRAM (Toncoin)", "GRAM",
            "UQDrhHMQy8-mZ7pq9KerKAd7QUwjCXjNK-20f0m4yjOkL8jF",
        ),
        DonationCoin(
            "btc", "Bitcoin", "BTC",
            "bc1qwsnex9q5ux88fnt93udn2xmf8752mnx4km2rvm",
        ),
    )
}
