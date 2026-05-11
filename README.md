# 70mai Dashcam Auto-Backup to SFTP (Android)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![70mai](https://img.shields.io/badge/Dashcam-70mai-orange.svg)](#)

A professional Android application designed for **70mai dashcam reverse engineering** and **automatic file synchronization**. It transfers videos and photos from 70mai dash cameras to a private SFTP storage via a secure Wi-Fi handshake.

### Optimized for Search Engines (SEO Keywords)
`70mai dashcam backup`, `70mai protocol reverse engineering`, `70mai A500S video transfer`, `70mai A800S Android app`, `auto dashcam sync SFTP`, `dashcam to cloud backup`, `70mai Wi-Fi protocol`, `com.lz1bgb.camhttp`.

## 🚀 Key Features

- **Hybrid Networking**: Automatically binds HTTP requests to the dashcam's Wi-Fi network (192.168.0.1) even when mobile data is active. This allows the app to talk to the camera and the internet (SFTP) simultaneously.
- **70mai Protocol Implementation**: Full implementation of the reverse-engineered 70mai handshake:
    1. `BindByBanya`: Initial pairing request.
    2. `UserconfirmByBanya`: Waiting for physical button press on the dashcam.
    3. `client.cgi`: Session registration and keep-alive.
- **Data Integrity**: 
    - Downloads files using a prioritized queue (oldest first).
    - Verifies file existence and size on the phone before sending the delete command to the camera.
    - Skips the most recent files (last 3 minutes) to ensure they are fully finalized by the camera.
- **Background Operation**: Runs as a foreground service with a persistent notification, ensuring Android doesn't kill the process during long transfers.
- **Statistics & Monitoring**: Real-time tracking of remaining files, download duration, and transfer status.

## 🛠 Technical Implementation

### Networking
The app uses `OkHttpClient` with custom `SocketFactory` binding. When a target URL contains `192.168.0.1`, the app forces the traffic through the Wi-Fi interface, bypassing the system's default route (which usually prefers mobile data when Wi-Fi has no internet).

### File Management
1. **Camera Stage**: Scans all CGI types (0-15). Filters out files currently being recorded. Downloads via HTTP.
2. **Local Stage**: Files are stored in `/Android/data/com.lz1bgb.camhttp/files/camera/` preserving the original directory structure (e.g., `/Normal/Front/`).
3. **Archive Stage**: If the camera is not in range, the app automatically switches to SFTP mode and uploads the local files to the configured server, deleting them locally upon success.

## ⚖️ Licensing

This project is subject to a **Dual License** model:

### 1. Non-Commercial Use
For personal, educational, or non-profit use, this software is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the `LICENSE` file for details.

### 2. Commercial Use
Any use within a commercial entity, for-profit project, or redistribution as part of a paid product requires a separate **Commercial License**. 
- You **must** obtain written consent from the author.
- For inquiries and commercial terms, please contact: **lz1bgb@gmail.com**

See `LICENSE-COMMERCIAL` for more information.

---
*Developed by [Sr-bgb](https://github.com/Sr-bgb)*
