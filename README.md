# Device Connect

**NO WAR**

Yet another tool to connect Android phone with desktop similar to KDE Connect.
Android client for https://github.com/cyanomiko/dcnnt-py

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/net.dcnnt/)

## Features

* Lightweight and fast
* AES-256 encryption with password
* Upload files from phone to desktop
* Open files and web URLs from phone on desktop
* Download files from pre-defined directories at desktop to phone
* Show phone notification on desktop
* Execute pre-defined commands on desktop
* Periodically running sync tasks (directories, contacts, SMS)

## Quickstart

### Prerequisites

0. Phone and PC must be in same broadcast domain, e.g., same Wi-Fi.
1. Server part of **dcnnt** must be installed on PC. Check: `dcnnt --help`, install: https://github.com/cyanomiko/dcnnt-py#install  
2. Client app must be installed on phone.

### How to pair devices (easy way)

1. Start server on your PC in pairing mode with `dcnnt pair` command. Pairing code of 6 digits will be printed in terminal (example: `123-456`).
2. Launch app on your phone.
3. Select `Devices` entry in navigation menu.
4. Tap `Search` button.
5. Input pair code in dialog on phone.

### How to pair devices 

1. Start server on your PC with `dcnnt` command.
2. Launch app on your phone.
3. Select `Settings` entry in navigation menu.
4. Set password in `Device password` field. 
3. Select `Devices` entry in navigation menu.
4. Tap `Search` button.
5. Tile with PC info (UIN, name and IP address) will appears in list under the `Search` button. Tap it.
6. Set device password, that defined in `conf.json` file in `password` field of `self` section.
7. On PC - go to `devices` subdirectory of dcnnt configuration directory (`$HOME/.config/dcnnt` by default).
8. Edit configuration file of the device (`${uin_of_the_device}.device.json`): set password from *step 4* to the `password` field (about config files: https://github.com/cyanomiko/dcnnt-py/blob/master/doc/config.md). 
