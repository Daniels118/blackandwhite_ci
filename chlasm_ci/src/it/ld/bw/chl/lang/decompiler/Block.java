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

class Block {
	public BlockType type;
	public int begin;
	public int end;
	public int farEnd;
	public int exceptionHandlerBegin;
	
	public Block(int begin, BlockType type, int end) {
		this(begin, type, end, -1);
	}
	
	public Block(int begin, BlockType type, int end, int exceptionHandlerBegin) {
		this.begin = begin;
		this.type = type;
		this.end = end;
		this.exceptionHandlerBegin = exceptionHandlerBegin;
	}
	
	public boolean is(BlockType...types) {
		for (BlockType t : types) {
			if (type == t) return true;
		}
		return false;
	}
	
	@Override
	public String toString() {
		return type.name();
	}
}