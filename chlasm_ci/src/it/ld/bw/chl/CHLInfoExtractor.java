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
package it.ld.bw.chl;

import java.io.PrintStream;
import java.util.List;

import it.ld.bw.chl.model.AutoStartScripts;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.Code;
import it.ld.bw.chl.model.DataSection;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.Script;
import it.ld.bw.chl.model.Scripts;

public class CHLInfoExtractor {
	private PrintStream out;
	
	public CHLInfoExtractor() {
		this(System.out);
	}
	
	public CHLInfoExtractor(PrintStream out) {
		this.out = out;
	}
	
	public void printInfo(CHLFile chl) {
		//File version
		int ver = chl.header.getVersion();
		out.println("Version: "+ver);
		out.println();
		//Number of global variables
		List<String> globalVars = chl.globalVars.getNames();
		out.println("Number of globals: "+globalVars.size());
		//Scripts offset
		Scripts scriptsSection = chl.scripts;
		//Scripts count
		List<Script> scripts = scriptsSection.getItems();
		out.println("Scripts count: "+scripts.size());
		//Scripts list
		for (int i = 0; i < scripts.size(); i++) {
			Script s1 = scripts.get(i);
			out.println("  "+s1.getScriptID()+": "+s1.getSignature()
					+" [source: "+s1.getSourceFilename()+", ip: "+s1.getInstructionAddress()
					+", globals: "+s1.getGlobalCount()
					+", locals: "+s1.getVariables()+"]");
		}
		//Autostart scripts offset
		AutoStartScripts autostartSection = chl.autoStartScripts;
		//Autostart scripts count
		List<Integer> autostartScripts = autostartSection.getScripts();
		out.println("Autostart scripts count: "+autostartScripts.size());
		//Autostart scripts list
		for (int i = 0; i < autostartScripts.size(); i++) {
			int scriptId = autostartScripts.get(i);
			out.println("  Autostart scripts["+i+"]: "+scriptId);
		}
		//Data offset
		DataSection dataSection = chl.data;
		//Data length
		byte[] data = dataSection.getData();
		out.println("Data length: "+data.length);
		//Code offset
		Code code = chl.code;
		//Number of instructions
		List<Instruction> instructions = code.getItems();
		out.println("Number of instructions: "+instructions.size());
	}
}
