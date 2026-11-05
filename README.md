# ⚡ Crypto Sentinel for Android

A native Android application designed for 24/7 real-time crypto order flow monitoring, Cumulative Volume Delta (CVD) tracking, and leading signal alerts.

---

## 📱 Features

* **24/7 Foreground Background Service:** Keeps monitoring WebSocket feeds even when you swipe the app away or lock your phone screen.
* **Heads-Up Push Notifications:** Alerts with sound and vibration on large Whale Orders ($100k+), Bullish/Bearish Absorptions, and Squeeze setups.
* **Live Watchlist Cards:** Real-time prices, 5M CVD, and Buy Ratio for **BTC, ETH, SOL, DOGE, XRP, SUI, PEPE, and NEAR**.
* **Integrated Paper Trading Scorecard:** Tracks simulated win-rate and PnL right from your phone.
* **OLED Dark Theme:** Battery-efficient interface built with Material Components.

---

## 🛠️ How to Get Your `.apk` File (Automated Cloud Build)

This repository includes a GitHub Actions workflow (`.github/workflows/build-apk.yml`) that compiles the APK automatically on high-speed cloud runners.

### Step 1: Create a GitHub Repository
1. Open [github.com/new](https://github.com/new) in your phone or browser.
2. Create a new repository named `crypto-sentinel-android` (Public or Private).

### Step 2: Push the Code
In your Termux terminal, run:
```bash
cd ~/projects/crypto-sentinel-android
git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/crypto-sentinel-android.git
git push -u origin main
```

### Step 3: Download & Install the APK
1. Go to your repository on GitHub.
2. Tap the **Actions** tab (or **Releases**).
3. Under the latest workflow run, tap **CryptoSentinel.apk** to download.
4. Open the downloaded APK on your phone and tap **Install**!
