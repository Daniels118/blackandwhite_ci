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

import static it.ld.bw.chl.lang.Utils.format;

import it.ld.bw.chl.lang.Type;
import it.ld.bw.chl.lang.Var;
import it.ld.bw.chl.model.NativeFunction.ArgType;

class Expression {
	final String value;
	public final boolean lowPriority;
	public Type type;
	final Integer intVal;
	private final Float floatVal;
	private final Boolean boolVal;
	private final Var var;
	public final boolean isExpression;
	
	public Expression(String value) {
		this(value, false, null, 0);
	}
	
	public Expression(int intVal) {
		this(null, false, Type.INT, intVal);
	}
	
	public Expression(float floatVal) {
		this(null, false, Type.FLOAT, floatVal);
	}
	
	public Expression(boolean boolVal) {
		this(null, false, Type.BOOL, boolVal);
	}
	
	public Expression(Var var) {
		this(null, false, var.type, var);
	}
	
	public Expression(String value, Var var) {
		this(value, false, null, var);
	}
	
	public Expression(String value, boolean lowPriority) {
		this(value, lowPriority, null, 0);
	}
	
	public Expression(String value, ArgType type, String specificType) {
		this(value, false,
				type == null ? null : new Type(type, specificType),
				(Integer)null);
	}
	
	public Expression(String value, boolean lowPriority, ArgType type, String specificType) {
		this(value, lowPriority,
				type == null ? null : new Type(type, specificType),
				(Integer)null);
	}
	
	public Expression(String value, Type type) {
		this(value, false, type, (Integer)null);
	}
	
	public Expression(String value, int intVal) {
		this(value, false, Type.INT, intVal);
	}
	
	public Expression(String value, boolean lowPriority, Type type, Integer intVal) {
		this.isExpression = value != null;
		this.value = isExpression ? value : String.valueOf(intVal);
		this.lowPriority = lowPriority;
		this.type = type;
		this.intVal = intVal;
		this.floatVal = null;
		this.boolVal = null;
		this.var = null;
	}
	
	public Expression(String value, boolean lowPriority, Type type, Float floatVal) {
		this.isExpression = value != null;
		this.value = isExpression ? value : format(floatVal);
		this.lowPriority = lowPriority;
		this.type = type;
		this.intVal = null;
		this.floatVal = floatVal;
		this.boolVal = null;
		this.var = null;
	}
	
	public Expression(String value, boolean lowPriority, Type type, Boolean boolVal) {
		this.isExpression = value != null;
		this.value = isExpression ? value : String.valueOf(boolVal);
		this.lowPriority = lowPriority;
		this.type = type;
		this.intVal = null;
		this.floatVal = null;
		this.boolVal = boolVal;
		this.var = null;
	}
	
	public Expression(String value, boolean lowPriority, Type type, Var var) {
		this.isExpression = value != null;
		this.value = isExpression ? value : var.name;
		this.lowPriority = lowPriority;
		this.type = type;
		this.intVal = 0;
		this.floatVal = 0f;
		this.boolVal = false;
		this.var = var;
	}
	
	public boolean isNumber() {
		if (type == null) return false;
		return type.type == ArgType.FLOAT || type.type == ArgType.INT;
	}
	
	public boolean isBool() {
		if (type == null) return false;
		return type.type == ArgType.BOOL;
	}
	
	public boolean isVar() {
		return var != null;
	}
	
	public Integer intVal() {
		if (!isNumber()) {
			throw new RuntimeException("Not a number");
		}
		return intVal;
	}
	
	public Float floatVal() {
		if (!isNumber()) {
			throw new RuntimeException("Not a number");
		}
		return floatVal;
	}
	
	public Boolean boolVal() {
		if (!isBool()) {
			throw new RuntimeException("Not a bool");
		}
		return boolVal;
	}
	
	public Var getVar() {
		if (!isVar()) {
			throw new RuntimeException("Not a variable");
		}
		return var;
	}
	
	public String wrap() {
		return isExpression ? "("+value+")" : value;
	}
	
	public String safe() {
		return lowPriority ? "("+value+")" : value;
	}
	
	@Override
	public String toString() {
		return value;
	}
}