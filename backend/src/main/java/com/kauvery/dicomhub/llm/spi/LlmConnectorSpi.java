package com.kauvery.dicomhub.llm.spi;

import com.kauvery.dicomhub.domain.entity.LlmApprovedModel;
import com.kauvery.dicomhub.domain.entity.LlmPromptTemplate;
import java.nio.file.Path;
import java.util.List;

/**
 * Phase 2 Multimodal LLM Connector SPI (BRD Section 6.8, FR-38 to FR-45 & ADR-05).
 */
public interface LlmConnectorSpi {

    record LlmInferenceResponse(
            boolean valid,
            String modelIdentifier,
            String pinnedVersion,
            String rawJsonReply,
            String validationError
    ) {}

    LlmInferenceResponse invokeMultimodalDraftAssist(
            LlmApprovedModel approvedModel,
            LlmPromptTemplate template,
            String sanitizedPromptText,
            List<Path> windowedSliceImages);
}
