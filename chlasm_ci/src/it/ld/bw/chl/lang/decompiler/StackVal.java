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

import it.ld.bw.chl.exceptions.DecompileException;
import it.ld.bw.chl.model.NativeFunction.ArgType;

class StackVal {
	public final ArgType type;
	private Integer intVal;
	private Float floatVal;
	private Boolean boolVal;
	
	public StackVal(ArgType type) {
		this.type = type;
	}
	
	public StackVal(ArgType type, int intVal) {
		this.type = type;
		this.intVal = intVal;
	}
	
	public StackVal(ArgType type, float floatVal) {
		this.type = type;
		this.floatVal = floatVal;
	}
	
	public StackVal(ArgType type, boolean boolVal) {
		this.type = type;
		this.boolVal = boolVal;
	}
	
	public int intVal() throws DecompileException {
		if (intVal == null) throw new DecompileException("Unknown stack value");
		return intVal;
	}
	@Override
	public String toString() {
		String r = type.toString();
		if (intVal != null) {
			r += " " + intVal;
		} else if (floatVal != null) {
			r += " " + floatVal;
		} else if (boolVal != null) {
			r += " " + boolVal;
		}
		return r;
	}
}