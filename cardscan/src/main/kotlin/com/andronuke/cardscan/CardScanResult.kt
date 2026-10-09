package com.andronuke.cardscan

/** Transient scan result. Do not persist or log cardholder data. */
class CardScanResult(@JvmField val cardNumber: String, @JvmField val expiry: String?)
