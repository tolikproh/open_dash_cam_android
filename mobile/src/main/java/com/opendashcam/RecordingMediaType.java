package com.opendashcam;

public enum RecordingMediaType {
    VIDEO("Video", "mp4", "video/mp4", "mp4"),
    AUDIO("Audio", "mp3", "audio/mpeg", "mp3"),
    AUDIO_MARKER("AudioMarker", "mp4", "video/mp4", "mp3");

    private final String folderName;
    private final String extension;
    private final String mimeType;
    private final String hiddenExtension;

    RecordingMediaType(String folderName, String extension, String mimeType, String hiddenExtension) {
        this.folderName = folderName;
        this.extension = extension;
        this.mimeType = mimeType;
        this.hiddenExtension = hiddenExtension;
    }

    public String getFolderName() {
        return folderName;
    }

    public String getExtension() {
        return extension;
    }

    public String getHiddenExtension() {
        return hiddenExtension;
    }

    public String getMimeType() {
        return mimeType;
    }

    public static RecordingMediaType fromPath(String path) {
        if (path == null) {
            return VIDEO;
        }
        String lower = path.toLowerCase();
        if (lower.contains("/audiomarker/") || lower.contains("/audiomarker%2f")) {
            return AUDIO_MARKER;
        }
        if (lower.endsWith(".mp3")) {
            return AUDIO;
        }
        return VIDEO;
    }
}
