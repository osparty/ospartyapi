package net.osparty.api.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One party member: display {@code name} plus the stable {@code accountHash} used by
 * clients to match blocked/favourited players across name changes. {@code accountHash}
 * is {@code 0} when the reporting client didn't supply one.
 *
 * <p>{@link MemberDeserializer} accepts both the current object form and the legacy wire
 * form where a member was a bare JSON string (so ads persisted in Redis before this change
 * still deserialise).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonDeserialize(using = Member.MemberDeserializer.class)
public class Member {
	private String name;
	private long accountHash;

	public static class MemberDeserializer extends JsonDeserializer<Member> {
		@Override
		public Member deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			if (p.currentToken() == JsonToken.VALUE_STRING) {
				return new Member(p.getValueAsString(), 0L);
			}
			JsonNode node = p.getCodec().readTree(p);
			if (node.isTextual()) {
				return new Member(node.asText(), 0L);
			}
			String name = node.hasNonNull("name") ? node.get("name").asText() : null;
			long accountHash = node.hasNonNull("accountHash") ? node.get("accountHash").asLong() : 0L;
			return new Member(name, accountHash);
		}
	}
}
