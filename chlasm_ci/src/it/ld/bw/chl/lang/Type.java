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

import it.ld.bw.chl.model.NativeFunction.ArgType;

public class Type {
	public static final Type UNKNOWN = new Type(ArgType.UNKNOWN);
	public static final Type INT = new Type(ArgType.INT);
	public static final Type FLOAT = new Type(ArgType.FLOAT);
	public static final Type BOOL = new Type(ArgType.BOOL);
	public static final Type COORD = new Type(ArgType.COORD);
	public static final Type OBJECT = new Type(ArgType.OBJECT);
	
	public final ArgType type;
	public final String specificType;
	
	public Type(ArgType type) throws NullPointerException {
		this.type = type;
		this.specificType = null;
	}
	
	public Type(ArgType type, String specificType) throws IllegalArgumentException {
		this.type = type;
		this.specificType = specificType;
		if (type == null) {
			throw new IllegalArgumentException("Type cannot be null");
		}
	}
	
	public boolean isEnum() {
		return type.isEnum;
	}
	
	public boolean isGenericEnum() {
		return type.isEnum && specificType == null;
	}
	
	public boolean isSpecificEnum() {
		return type.isEnum && specificType != null;
	}
	
	public boolean isObject() {
		return type == ArgType.OBJECT;
	}
	
	public boolean isGenericObject() {
		return type == ArgType.OBJECT && specificType == null;
	}
	
	public boolean isSpecificObject() {
		return type == ArgType.OBJECT && specificType != null;
	}
	
	@Override
	public int hashCode() {
		return type.hashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Type)) return false;
		Type other = (Type) obj;
		if (this.type != other.type) return false;
		if (this.specificType == null && other.specificType != null) {
			return false;
		}else if (this.specificType != null && other.specificType == null) {
			return false;
		} else if (this.specificType != null && other.specificType != null) {
			if (!this.specificType.equals(other.specificType)) return false;
		}
		return true;
	}
	
	public String toString() {
		if (type == ArgType.OBJECT) {
			if (specificType != null) {
				return type.keyword+"<"+specificType+">";
			} else {
				return type.keyword;
			}
		} else if (specificType != null) {
			return specificType;
		} else {
			return type.keyword;
		}
	}
}