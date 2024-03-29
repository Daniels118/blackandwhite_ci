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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import it.ld.bw.chl.exceptions.InvalidVariableIdException;
import it.ld.utils.EndianDataInputStream;
import it.ld.utils.EndianDataOutputStream;

public class Script extends Struct {
	private CHLFile chl;
	
	private String name;
	private String sourceFilename;
	private ScriptType scriptType;
	/**The number of global variables defined so far. Values greater than this are mapped to local params/vars */
	private int globalCount = 0;
	/**Parameters + local variables*/
	private List<String> variables = new LinkedList<String>();
	/**Index of the first instruction in the instructions array*/
	private int instructionAddress = -1;
	/**How many local variables are parameters*/
	private int parameterCount;
	private int scriptID;
	
	/**Which parameters are reference to global variables*/
	private Set<String> references = new HashSet<>();
	
	private Map<String, Integer> localsMap = null;
	private int lastInstructionAddress = -1;
	
	public Script(CHLFile chl) {
		this.chl = chl;
	}
	
	public CHLFile getChl() {
		return chl;
	}
	
	public void setChl(CHLFile chl) {
		this.chl = chl;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getSourceFilename() {
		return sourceFilename;
	}
	
	public void setSourceFilename(String sourceFilename) {
		this.sourceFilename = sourceFilename;
	}
	
	public ScriptType getScriptType() {
		return scriptType;
	}
	
	public void setScriptType(ScriptType scriptType) {
		this.scriptType = scriptType;
	}
	
	public int getGlobalCount() {
		return globalCount;
	}
	
	public void setGlobalCount(int globalCount) {
		this.globalCount = globalCount;
	}
	
	public List<String> getVariables() {
		return variables;
	}
	
	public void setVariables(List<String> variables) {
		this.variables = variables;
	}
	
	public int getLocalVarIndex(String varName) {
		if (localsMap == null) {
			localsMap = new HashMap<>();
			int i = 0;
			Iterator<String> it = variables.iterator();
			while (it.hasNext()) {
				String tName = it.next();
				localsMap.put(tName, i++);
			}
		}
		return localsMap.getOrDefault(varName, -1);
	}
	
	public void setReference(String varName) throws IllegalArgumentException {
		int i = getLocalVarIndex(varName);
		if (i < 0) throw new IllegalArgumentException("Variable "+varName+" is not a parameter of script "+this.name);
		references.add(varName);
	}
	
	public boolean isReference(String varName) throws IllegalArgumentException {
		int i = getLocalVarIndex(varName);
		if (i < 0) throw new IllegalArgumentException("Variable "+varName+" is not a parameter of script "+this.name);
		return references.contains(varName);
	}
	
	public List<String> getVariablesWithoutParameters() {
		List<String> res = new ArrayList<>(variables.size() - parameterCount);
		ListIterator<String> it = variables.listIterator(parameterCount);
		while (it.hasNext()) {
			res.add(it.next());
		}
		return res;
	}
	
	public int getInstructionAddress() {
		return instructionAddress;
	}
	
	public void setInstructionAddress(int instructionAddress) {
		this.instructionAddress = instructionAddress;
	}
	
	public int getParameterCount() {
		return parameterCount;
	}
	
	public void setParameterCount(int parameterCount) {
		this.parameterCount = parameterCount;
	}
	
	public int getScriptID() {
		return scriptID;
	}
	
	public void setScriptID(int scriptID) {
		this.scriptID = scriptID;
	}
	
	public int getLastInstructionAddress() {
		if (lastInstructionAddress < instructionAddress) {
			ArrayList<Instruction> instructions = chl.code.getItems();
			for (int i = instructionAddress; i < instructions.size(); i++) {
				if (instructions.get(i).opcode == OPCode.END) {
					lastInstructionAddress = i;
					break;
				}
			}
		}
		return lastInstructionAddress;
	}
	
	@Override
	public void read(EndianDataInputStream str) throws Exception {
		name = readZString(str);
		sourceFilename = readZString(str);
		scriptType = ScriptType.fromCode(str.readInt());
		globalCount = str.readInt();
		variables = readZStringArray(str);
		instructionAddress = str.readInt();
		parameterCount = str.readInt();
		scriptID = str.readInt();
	}
	
	@Override
	public void write(EndianDataOutputStream str) throws Exception {
		writeZString(str, name);
		writeZString(str, sourceFilename);
		str.writeInt(scriptType.code);
		str.writeInt(globalCount);
		writeZStringArray(str, variables);
		str.writeInt(instructionAddress);
		str.writeInt(parameterCount);
		str.writeInt(scriptID);
	}
	
	public boolean isGlobalVar(int varId) {
		return varId >= 1 && varId <= globalCount;
	}
	
	public boolean isLocalVar(int varId) {
		return varId > globalCount;
	}
	
	public String getLocalVar(int varId) throws InvalidVariableIdException {
		if (!isLocalVar(varId)) throw new InvalidVariableIdException(varId);
		int index = varId - globalCount - 1;
		if (index < 0 || index >= variables.size()) {
			throw new InvalidVariableIdException(varId);
		}
		return variables.get(index);
	}
	
	public String getGlobalVar(CHLFile chl, int varId) {
		if (!isGlobalVar(varId)) {
			throw new InvalidVariableIdException(varId);
		}
		return chl.globalVars.getNames().get(varId - 1);
	}
	
	public String getVar(CHLFile chl, int varId) {
		if (isLocalVar(varId)) {
			return getLocalVar(varId);
		} else {
			return getGlobalVar(chl, varId);
		}
	}
	
	/**Returns the signature of this script in the following format:
	 * script_type script_name(parameter1, parameter2...)
	 * If there are no parameters, the parentheses are omitted.
	 * @return
	 */
	public String getSignature() {
		String res = scriptType.keyword + " " + name;
		if (parameterCount > 0) {
			String[] argNames = new String[parameterCount];
			for (int i = 0; i < parameterCount; i++) {
				String argName = variables.get(i);
				argNames[i] = (references.contains(argName) ? "*" : "") + argName;
			}
			res += "(" + String.join(", ", argNames) + ")";
		}
		return res;
	}
	
	@Override
	public String toString() {
		String addr = String.format("0x%1$08X", instructionAddress);
		return "script[" + scriptID + "]: " + getSignature() + " at " + addr + " in " + sourceFilename;
	}
}
