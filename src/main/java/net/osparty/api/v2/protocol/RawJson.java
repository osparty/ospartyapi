package net.osparty.api.v2.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import java.io.IOException;

/**
 * Captures a JSON value without interpreting it, for payloads the server only ever passes through.
 *
 * <p>A member's live state is opaque here by design, yet holding it as a {@code JsonNode} made the server
 * build a whole node tree for it on the way in and walk that tree again on the way out — an {@code ObjectNode}
 * plus a boxed node per field, per update, per member, allocated and discarded on the hottest path in the
 * system purely to reproduce bytes we already had.
 *
 * <p>A {@link TokenBuffer} holds the same content as a flat run of tokens and replays it straight into the
 * outgoing generator. No node objects, no tree walk, same bytes out as came in.
 *
 * <p>Only for values the server does not read. Anything it needs to inspect or compare — the ad meta, which
 * is diffed against the previous one — stays a {@code JsonNode}.
 */
public final class RawJson extends JsonDeserializer<TokenBuffer> {
	@Override
	public TokenBuffer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
		TokenBuffer buffer = new TokenBuffer(parser);
		buffer.copyCurrentStructure(parser);
		return buffer;
	}
}
