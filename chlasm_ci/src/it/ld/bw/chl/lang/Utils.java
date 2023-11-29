/* Copyright (c) 2023 Daniele Lombardi / Daniels118
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.ld.bw.chl.lang;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;

public final class Utils {
	private static final char[] ILLEGAL_CHARACTERS = {'/', '\n', '\r', '\t', '\0', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':'};
	
	private static final int SIGNIFICANT_DIGITS = 8;
	private static final DecimalFormat decimalFormat = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
	
	private Utils() {}
	
	public static <T> T getLast(List<T> list) {
		return list.get(list.size() - 1);
	}
	
	public static <T> T pop(List<T> stack) {
		return stack.remove(stack.size() - 1);
	}
	
	public static String escape(String string) {
		string = string.replace("\\", "\\\\");
		string = string.replace("\"", "\\\"");
		return "\"" + string + "\"";
	}
	
	public static String format(float v) {
		//return Integer.toHexString(Float.floatToRawIntBits(v));
		/*if (Math.abs(v) <= 16777216 && (int)v == v) {
			return String.valueOf((int)v);
		}*/
		decimalFormat.setMaximumFractionDigits(SIGNIFICANT_DIGITS - 1);
		String r = decimalFormat.format(v);
		int nInt = r.indexOf('.');	//Compute the number of int digits
		if (nInt > 1) {
			int nDec = Math.max(1, Math.min(SIGNIFICANT_DIGITS - nInt, SIGNIFICANT_DIGITS - 1));
			decimalFormat.setMaximumFractionDigits(nDec);
			r = decimalFormat.format(v);
		}
		return r;
	}
	
	public static boolean isValidFilename(String s) {
		for (char c : ILLEGAL_CHARACTERS) {
			if (s.indexOf(c) >= 0) return false;
		}
		return true;
	}
	
	public static String addSuffix(File file, String suffix) {
		String fullname = file.getName();
		String name = fullname;
		String extension = "";
		int p = fullname.lastIndexOf('.');
		if (p >= 0) {
			name = fullname.substring(0, p);
			extension = fullname.substring(p);
		}
		return name + suffix + extension;
	}
	
	public static String join(String separator, Object[] items) {
		if (items.length == 0) return "";
		StringBuilder res = new StringBuilder(16 * items.length);
		res.append(String.valueOf(items[0]));
		for (int i = 1; i < items.length; i++) {
			res.append(separator);
			res.append(String.valueOf(items[i]));
		}
		return res.toString();
	}
	
	public static void skipNulls(ListIterator<?> iterator) {
		if (iterator.hasNext()) {
			Object item = iterator.next();
			while (item == null) {
				item = iterator.next();
			}
			iterator.previous();
		}
	}
	
	public static ByteBuffer resize(ByteBuffer buffer, int capacity) {
        ByteBuffer newBuffer = ByteBuffer.allocate(capacity);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }
	
	public static float asFloat(Object v) {
		if (v instanceof Double) {
			return ((Double)v).floatValue();
		} else if (v instanceof Integer) {
			return ((Integer)v).floatValue();
		} else {
			throw new IllegalArgumentException("Invalid numeric object: "+v);
		}
	}
	
	public static int asInt(Object v) {
		if (v instanceof Double) {
			return ((Double)v).intValue();
		} else if (v instanceof Integer) {
			return ((Integer)v).intValue();
		} else {
			throw new IllegalArgumentException("Invalid numeric object: "+v);
		}
	}
	
	public static Object parseImmed(String s) {
		if ("true".equals(s)) return Boolean.TRUE;
		if ("false".equals(s)) return Boolean.FALSE;
		Object v = parseString(s);
		if (v != null) return v;
		if (s.indexOf('.') >= 0) {
			v = parseFloat(s);
			if (v != null) return v;
		}
		v = parseInt(s);
		return v;
	}
	
	public static String parseString(String s) {
		if (!s.startsWith("\"") || !s.endsWith("\"")) return null;
		s = s.substring(1, s.length() - 1);
		s = s.replace("\\\\", "\\");
		s = s.replace("\\\"", "\"");
		s = s.replace("\\r", "\r");
		s = s.replace("\\n", "\n");
		s = s.replace("\\t", "\t");
		return s;
	}
	
	public static Float parseFloat(String s) {
		try {
	        return Float.parseFloat(s);
	    } catch (NumberFormatException e) {
	        return null;
	    }
	}
	
	public static Integer parseInt(String s) {
		try {
			if (s.startsWith("0x")) {
				return Integer.parseInt(s.substring(2), 16);
			} else {
				return Integer.parseInt(s);
			}
	    } catch (NumberFormatException e) {
	        return null;
	    }
	}
	
	public static boolean isValidIdentifier(String s) {
	    if (s.isEmpty()) return false;
	    if ("true".equals(s)) return false;
	    if ("false".equals(s)) return false;
	    if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
	    for (int i = 1; i < s.length(); i++) {
	        if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
	    }
	    return true;
	}
	
	public static File find(File path, String filename) {
		File file = new File(path, filename);
		if (file.exists()) return file;
		for (File f : path.listFiles()) {
			if (f.isDirectory() && !".".equals(f.getName()) && !"..".equals(f.getName())) {
				file = find(f, filename);
				if (file != null) return file;
			}
		}
		return null;
	}
}
