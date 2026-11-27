package com.northstar.workflow.internal;

import com.northstar.workflow.ConfigProblem;
import com.northstar.workflow.WorkflowConfigInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A config sent as a request's body (step 3.1b; workflow-spec.md section 16): the text itself, as sent, for the workflow
 * service to read strictly ({@link ConfigJson}), never bound by the app's API mapper, which would keep the last of two
 * members with one name and lose the text. At most {@value ConfigJson#MAX_BYTES} bytes are read: a longer body is 422
 * {@code config_too_large} without reading past the limit, and bytes that are not UTF-8 are {@code json_invalid}. Both
 * depend on the bytes alone, so they are the same answer for every workflow ID.
 */
final class DraftBody {

    private DraftBody() {}

    static String read(HttpServletRequest request) {
        if (request.getContentLengthLong() > ConfigJson.MAX_BYTES) throw refused(ProblemCode.CONFIG_TOO_LARGE);
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(ConfigJson.MAX_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("The request body could not be read", e);
        }
        if (bytes.length > ConfigJson.MAX_BYTES) throw refused(ProblemCode.CONFIG_TOO_LARGE);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw refused(ProblemCode.JSON_INVALID);
        }
    }

    private static WorkflowConfigInvalidException refused(ProblemCode code) {
        return new WorkflowConfigInvalidException(List.of(new ConfigProblem(JsonPath.ROOT.toString(), code.code())));
    }
}
