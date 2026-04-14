package net.travelbag.util;

import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

public final class TextUtil {
	private TextUtil() {
	}

	public static MutableComponent fromLegacy(String input, Map<String, String> replacements) {
		String resolved = input;
		for (Map.Entry<String, String> entry : replacements.entrySet()) {
			resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
		}

		MutableComponent result = Component.empty();
		Style currentStyle = Style.EMPTY;
		StringBuilder buffer = new StringBuilder();

		for (int index = 0; index < resolved.length(); index++) {
			char current = resolved.charAt(index);
			if (current == '&' && index + 1 < resolved.length()) {
				ChatFormatting formatting = ChatFormatting.getByCode(resolved.charAt(index + 1));
				if (formatting != null) {
					if (!buffer.isEmpty()) {
						result.append(Component.literal(buffer.toString()).setStyle(currentStyle));
						buffer.setLength(0);
					}
					currentStyle = formatting == ChatFormatting.RESET ? Style.EMPTY : currentStyle.applyFormat(formatting);
					index++;
					continue;
				}
			}
			buffer.append(current);
		}

		if (!buffer.isEmpty()) {
			result.append(Component.literal(buffer.toString()).setStyle(currentStyle));
		}

		return result;
	}
}
