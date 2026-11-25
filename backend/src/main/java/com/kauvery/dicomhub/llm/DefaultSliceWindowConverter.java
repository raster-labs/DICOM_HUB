package com.kauvery.dicomhub.llm;

import com.kauvery.dicomhub.llm.spi.SliceWindowConverter;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Default key-slice selector and CT window converter stub (FR-40).
 */
@Component
public class DefaultSliceWindowConverter implements SliceWindowConverter {

    @Override
    public WindowedSliceSet convertKeySlices(
            List<Path> deidentifiedDicomFiles,
            String windowPreset,
            int maxSlices) {
        int limit = Math.max(1, maxSlices);
        List<Path> selected = deidentifiedDicomFiles.stream().limit(limit).toList();
        return new WindowedSliceSet(
                windowPreset == null ? "SOFT_TISSUE" : windowPreset,
                selected.size(),
                selected);
    }
}
