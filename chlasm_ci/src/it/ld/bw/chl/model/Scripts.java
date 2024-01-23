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
package it.ld.bw.chl.model;

import java.util.HashMap;
import java.util.Map;

import it.ld.bw.chl.exceptions.InvalidScriptIdException;
import it.ld.bw.chl.exceptions.ScriptNotFoundException;
import it.ld.utils.EndianDataInputStream;

public class Scripts extends StructArray<Script> {
	private CHLFile chl;
	
	private Map<String, Script> scriptsMap = new HashMap<>();
	private Map<Integer, Script> entrypointScripts = new HashMap<>();
	
	public Scripts(CHLFile chl) {
		this.chl = chl;
	}
	
	public Script getScriptFromInstruction(int instruction) {
		for (int i = entrypointScripts.size(); i < items.size(); i++) {
			Script script = items.get(i);
			entrypointScripts.put(script.getInstructionAddress(), script);
		}
		return entrypointScripts.get(instruction);
	}
	
	public Script getScriptFromEntrypoint(int ip) {
		if (entrypointScripts == null) {
			entrypointScripts = new HashMap<>();
			for (Script script : items) {
				entrypointScripts.put(script.getInstructionAddress(), script);
			}
		}
		return entrypointScripts.get(ip);
	}
	
	@Override
	public Class<Script> getItemClass() {
		return Script.class;
	}
	
	@Override
	public Script createItem() {
		return new Script(chl);
	}
	
	@Override
	public void read(EndianDataInputStream str) throws Exception {
		super.read(str);
	}
	
	public Script getScript(int scriptID) throws InvalidScriptIdException {
		for (Script script : items) {
			if (script.getScriptID() == scriptID) return script;
		}
		throw new InvalidScriptIdException(scriptID);
	}
	
	public Script getScript(String scriptName) throws ScriptNotFoundException {
		//Fast "running-cache" algorithm
		for (int i = scriptsMap.size(); i < items.size(); i++) {
			Script script = items.get(i);
			scriptsMap.put(script.getName(), script);
		}
		Script script = scriptsMap.get(scriptName);
		if (script != null) return script;
		throw new ScriptNotFoundException(scriptName);
	}
	
	@Override
	public String toString() {
		StringBuffer s = new StringBuffer(items.size() * 22);
		for (Script script : items) {
			s.append(script.toString() + "\r\n");
		}
		return s.toString();
	}
}
