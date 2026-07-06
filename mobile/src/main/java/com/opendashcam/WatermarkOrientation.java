package com.opendashcam;

import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.IOException;

/**
 * Derives watermark layout from encoded video metadata.
 * Strips always span the long edge of the frame.
 */
final class WatermarkOrientation {
    enum StripLayout {
        HORIZONTAL,
        VERTICAL
    }

    /** Length of watermark strips along the long edge of the frame. */
    final int stripSpan;
    final StripLayout stripLayout;

    private WatermarkOrientation(int stripSpan, StripLayout stripLayout) {
        this.stripSpan = stripSpan;
        this.stripLayout = stripLayout;
    }

    static WatermarkOrientation read(File inputFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(inputFile.getAbsolutePath());
            String widthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotationValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (widthValue == null || heightValue == null) {
                return new WatermarkOrientation(0, StripLayout.HORIZONTAL);
            }

            int encodedWidth = Integer.parseInt(widthValue);
            int encodedHeight = Integer.parseInt(heightValue);
            int rotationDegrees = rotationValue != null ? Integer.parseInt(rotationValue) : 0;

            int displayWidth = encodedWidth;
            int displayHeight = encodedHeight;
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                displayWidth = encodedHeight;
                displayHeight = encodedWidth;
            }

            int stripSpan = Math.max(displayWidth, displayHeight);
            StripLayout layout = displayWidth >= displayHeight
                    ? StripLayout.HORIZONTAL
                    : StripLayout.VERTICAL;
            return new WatermarkOrientation(stripSpan, layout);
        } catch (RuntimeException e) {
            return new WatermarkOrientation(0, StripLayout.HORIZONTAL);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
            }
        }
    }
}
