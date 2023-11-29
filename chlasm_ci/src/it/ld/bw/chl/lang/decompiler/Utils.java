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
package it.ld.bw.chl.lang.decompiler;

import java.io.File;
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
}
