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

public class Var {
	public final Scope scope;
	public final String name;
	public final int index;
	public int size;			//CI introduced arrays
	public final float val;		//CI introduced default value
	public boolean ref;
	public Type type;			//Guessed type
	
	public Var(Scope scope, String name, int index, int size, float val) {
		this(scope, name, index, size, val, false);
	}
	
	public Var(Scope scope, String name, int index, int size, float val, boolean ref) {
		if (size <= 0) throw new IllegalArgumentException("Invalid variable size: "+size);
		this.scope = scope;
		this.name = name;
		this.index = index;
		this.size = size;
		this.val = val;
		this.ref = ref;
	}
	
	public boolean isArray() {
		return size > 1;
	}
	
	@Override
	public String toString() {
		return scope + " variable " + name;
	}
}