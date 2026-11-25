package com.kauvery.dicomhub.llm.spi;

import java.nio.file.Path;
import java.util.List;

/**
 * SPI for converting selected de-identified DICOM slices into CT-windowed PNG/JPEG frames
 * for multimodal LLM input (BRD FR-40 & ADR-05).
 */
public interface SliceWindowConverter {

    record WindowedSliceSet(
            String windowPreset,
            int selectedSliceCount,
            List<Path> exportedImagePaths
    ) {}

    WindowedSliceSet convertKeySlices(
            List<Path> deidentifiedDicomFiles,
            String windowPreset,
            int maxSlices);
}
