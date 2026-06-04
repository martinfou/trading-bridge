"use strict";const e=require("electron");e.contextBridge.exposeInMainWorld("electronAPI",{platform:process.platform,isElectron:!0});
