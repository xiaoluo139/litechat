package com.google.android.accessibility.selecttospeak

import com.litechat.app.capture.ChatCaptureService

/**
 * The live capture service, registered under this system-style class name so
 * chat apps that obfuscate their node tree for ordinary accessibility services
 * still expose it. All logic lives in [ChatCaptureService]; only the class name
 * differs.
 *
 * Do not rename this class or its Manifest registration — the disguise is what
 * gets past that node obfuscation.
 */
class SelectToSpeakService : ChatCaptureService()
