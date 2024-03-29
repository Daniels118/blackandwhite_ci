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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Stack;

import it.ld.bw.chl.exceptions.CompileException;
import it.ld.bw.chl.exceptions.InvalidScriptIdException;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.NativeFunction;
import it.ld.bw.chl.model.OPCode;
import it.ld.bw.chl.model.Script;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.DataSection.StringData;
import it.ld.bw.chl.model.DataType;
import it.ld.bw.chl.model.ILabel;
import it.ld.bw.chl.model.InitGlobal;

import static it.ld.bw.chl.lang.Utils.*;

public class ASMWriter {
	//private static final Charset SRC_CHARSET = Charset.forName("ISO-8859-1");
	private static final Charset SRC_CHARSET = Charset.forName("windows-1252");
	
	private boolean printDataHintEnabled = true;
	private boolean printNativeInfoEnabled = true;
	private boolean printSourceLinenoEnabled = false;
	private boolean printSourceLineEnabled = false;
	private Path sourcePath = null;
	private boolean printSourceCommentsEnabled = false;
	private boolean printBinInfoEnabled = false;
	
	private Map<String, InitGlobal> initMap;
	
	private String currentSourceFilename;
	private String[] source;
	
	private PrintStream out;
	
	public ASMWriter() {
		this(System.out);
	}
	
	public ASMWriter(PrintStream out) {
		this.out = out;
	}
	
	public boolean isDataHintEnabled() {
		return printDataHintEnabled;
	}
	
	public void setPrintDataHintEnabled(boolean printDataHintEnabled) {
		this.printDataHintEnabled = printDataHintEnabled;
	}
	
	public boolean isPrintNativeInfoEnabled() {
		return printNativeInfoEnabled;
	}
	
	public void setPrintNativeInfoEnabled(boolean printNativeInfoEnabled) {
		this.printNativeInfoEnabled = printNativeInfoEnabled;
	}
	
	public boolean isPrintSourceLinenoEnabled() {
		return printSourceLinenoEnabled;
	}
	
	public void setPrintSourceLinenoEnabled(boolean printSourceLinenoEnabled) {
		this.printSourceLinenoEnabled = printSourceLinenoEnabled;
	}
	
	public boolean isPrintSourceLineEnabled() {
		return printSourceLineEnabled;
	}
	
	public void setPrintSourceLineEnabled(boolean printSourceLineEnabled) {
		this.printSourceLineEnabled = printSourceLineEnabled;
	}

	public Path getSourcePath() {
		return sourcePath;
	}
	
	public void setSourcePath(Path sourcePath) {
		this.sourcePath = sourcePath;
	}
	
	public boolean isPrintSourceCommentsEnabled() {
		return printSourceCommentsEnabled;
	}
	
	public void setPrintSourceCommentsEnabled(boolean printSourceCommentsEnabled) {
		this.printSourceCommentsEnabled = printSourceCommentsEnabled;
	}
	
	public boolean isPrintBinInfoEnabled() {
		return printBinInfoEnabled;
	}
	
	public void setPrintBinInfoEnabled(boolean printBinInfoEnabled) {
		this.printBinInfoEnabled = printBinInfoEnabled;
	}
	
	public void write(CHLFile chl, File outdir) throws IOException, CompileException, InvalidScriptIdException {
		Path path = outdir.toPath();
		//
		prepareInitGlobalMap(chl);
		List<StringData> constants = chl.data.getStrings();
		Map<Integer, StringData> constMap = mapConstants(constants);
		List<String> sources = chl.getSourceFilenames();
		Map<Integer, Label> labels = getLabels(chl);
		//
		out.println("Writing _project.txt");
		File prjFile = path.resolve("_project.txt").toFile();
		try (FileWriter str = new FileWriter(prjFile);) {
			str.write("source _data.txt\r\n");
			for (String sourceFilename : sources) {
				if (!isValidFilename(sourceFilename)) {
					throw new RuntimeException("Invalid source filename: " + sourceFilename);
				}
				str.write("source " + sourceFilename + "\r\n");
			}
			str.write("source _autorun.txt\r\n");
		}
		//
		out.println("Writing _data.txt");
		File dataFile = path.resolve("_data.txt").toFile();
		try (FileWriter str = new FileWriter(dataFile);) {
			writeHeader(chl, str);
			writeData(chl, str, constants);
		}
		//
		out.println("Writing _autorun.txt");
		File autostartFile = path.resolve("_autorun.txt").toFile();
		try (FileWriter str = new FileWriter(autostartFile);) {
			writeHeader(chl, str);
			writeAutoStartScripts(chl, str);
		}
		//
		for (String sourceFilename : sources) {
			if (!isValidFilename(sourceFilename)) {
				throw new RuntimeException("Invalid source filename: " + sourceFilename);
			}
			File sourceFile = path.resolve(sourceFilename).toFile();
			out.println("Writing "+sourceFilename);
			try (Writer str = new BufferedWriter(new FileWriter(sourceFile));) {
				writeHeader(chl, str);
				writeScripts(chl, str, sourceFilename, labels, constMap);
			}
		}
	}
	
	public void writeMerged(CHLFile chl, File file) throws IOException, CompileException {
		prepareInitGlobalMap(chl);
		List<StringData> constants = chl.data.getStrings();
		Map<Integer, StringData> constMap = mapConstants(constants);
		Map<Integer, Label> labels = getLabels(chl);
		try (Writer str = new BufferedWriter(new FileWriter(file));) {
			writeHeader(chl, str);
			writeData(chl, str, constants);
			writeScripts(chl, str, labels, constMap);
			writeAutoStartScripts(chl, str);
		}
	}
	
	private void prepareInitGlobalMap(CHLFile chl) {
		initMap = new HashMap<>();
		for (InitGlobal init : chl.initGlobals.getItems()) {
			initMap.put(init.getName(), init);
		}
	}
	
	private Map<Integer, Label> getLabels(CHLFile chl) {
		Map<Integer, Label> labels = new HashMap<>();
		List<Script> scripts = chl.scripts.getItems();
		List<Instruction> instructions = chl.code.getItems();
		for (Script script : scripts) {
			int labelCount = 0;
			String scriptName = script.getName();
			int ip = script.getInstructionAddress();
			ListIterator<Instruction> it = instructions.listIterator(ip);
			while (it.hasNext()) {
				Instruction instr = it.next();
				if (instr.opcode.isIP) {
					Label label = labels.get(instr.intVal);
					if (label == null) {
						label = new Label(scriptName, labelCount);
						labels.put(instr.intVal, label);
						labelCount++;
					}
					if (instr.opcode == OPCode.EXCEPT) {
						label.exceptionHandler = true;
					} else {
						if (instr.isForward()) {
							label.forwardReferenced = true;
						} else {
							label.backReferenced = true;
						}
					}
				}
				if (instr.opcode == OPCode.END) {
					break;
				}
				ip++;
			}
		}
		return labels;
	}
	
	private Map<Integer, StringData> mapConstants(List<StringData> constants) {
		Map<Integer, StringData> constMap = new HashMap<Integer, StringData>();
		for (StringData c : constants) {
			constMap.put(c.offset, c);
		}
		return constMap;
	}
	
	private void writeHeader(CHLFile chl, Writer str) throws IOException {
		str.write("//LHVM Challenge ASM version "+chl.header.getVersion()+"\r\n");
		str.write("\r\n");
	}
	
	private void writeData(CHLFile chl, Writer str, List<StringData> constants) throws IOException {
		str.write(".DATA\r\n");
		for (StringData c : constants) {
			str.write(c.getDeclaration() + "\r\n");
		}
		str.write("\r\n");
	}
	
	private void writeGlobals(CHLFile chl, Writer str, int start, int end) throws IOException {
		str.write(".GLOBALS");
		ListIterator<String> it = chl.globalVars.getNames().subList(start, end).listIterator();
		String name0 = null;
		InitGlobal init = null;
		int size = 1;
		while (it.hasNext()) {
			String name = it.next();
			if ("LHVMA".equals(name)) {	//Variable array hack (CI)
				if (init.getFloat() != 0) {
					throw new RuntimeException("Array initialization not supported at "+name0+"["+size+"]");
				}
				size++;
			} else {
				if (name0 != null) {
					if (size > 1) {
						str.write("["+size+"]");
						size = 1;
					} else if (init.getFloat() != 0) {
						str.write(" = "+init.getAsString());
					}
				}
				name0 = name;
				init = initMap.get(name);
				str.write("\r\nglobal "+name);
			}
		}
		if (name0 != null) {
			if (size > 1) {
				str.write("["+size+"]");
			} else if (init.getFloat() != 0) {
				str.write(" = "+init.getAsString());
			}
		}
		str.write("\r\n");
	}
	
	private void writeScripts(CHLFile chl, Writer str, Map<Integer, Label> labels, Map<Integer, StringData> constMap) throws IOException, CompileException {
		int firstGlobal = 0;
		String prevSourceFilename = "";
		List<Script> scripts = chl.scripts.getItems();
		for (Script script : scripts) {
			if (!script.getSourceFilename().equals(prevSourceFilename)) {
				str.write("\r\n");
				str.write("SOURCE "+script.getSourceFilename()+"\r\n");
				str.write("\r\n");
				writeGlobals(chl, str, firstGlobal, script.getGlobalCount());
				firstGlobal = script.getGlobalCount();
				str.write("\r\n");
				str.write(".SCRIPTS\r\n");
				str.write("\r\n");
				prevSourceFilename = script.getSourceFilename();
			}
			writeScript(chl, str, script, labels, constMap);
			str.write("\r\n");
		}
		str.write("\r\n");
	}
	
	private void writeScripts(CHLFile chl, Writer str, String sourceFilename, Map<Integer, Label> labels, Map<Integer, StringData> constMap) throws IOException, CompileException {
		int firstGlobal = 0;
		Script script = null;
		ListIterator<Script> it = chl.scripts.getItems().listIterator();
		while (it.hasNext()) {
			script = it.next();
			if (script.getSourceFilename().equals(sourceFilename)) {
				it.previous();
				break;
			}
			firstGlobal = script.getGlobalCount();
		}
		writeGlobals(chl, str, firstGlobal, script.getGlobalCount());
		firstGlobal = script.getGlobalCount();
		str.write("\r\n");
		str.write(".SCRIPTS\r\n");
		str.write("\r\n");
		while (it.hasNext()) {
			script = it.next();
			if (!script.getSourceFilename().equals(sourceFilename)) {
				break;
			}
			writeScript(chl, str, script, labels, constMap);
			str.write("\r\n");
		}
	}
	
	private void writeScript(CHLFile chl, Writer str, Script script, Map<Integer, Label> labels, Map<Integer, StringData> constMap) throws IOException, CompileException {
		if (printSourceLineEnabled) {
			setSourceFile(script.getSourceFilename());
		}
		Stack<String> comments = new Stack<>();
		List<Instruction> instructions = chl.code.getItems();
		final int firstInstruction = script.getInstructionAddress();
		Instruction instr;
		//Script comments
		if (printSourceCommentsEnabled && source != null) {
			instr = instructions.get(firstInstruction + 1);
			if (instr.lineNumber <= 0) {
				//In case there are no parameters and local vars
				instr = instructions.get(firstInstruction + 2);
			}
			//Search for previous comments
			for (int i = instr.lineNumber - 2; i >= 0; i--) {
				String src = i < source.length ? source[i] : "";
				String srcT = src.trim();
				if (src.isBlank() || srcT.startsWith("//")) {
					comments.push(src);
				} else if ("start".equals(srcT) || srcT.startsWith("begin ")) {
					comments.clear();
				} else {
					break;
				}
			}
			//Write previous comments
			while (!comments.isEmpty()) {
				str.write(comments.pop() + "\r\n");
			}
		}
		//Signature
		str.write("begin "+script.getSignature()+"\r\n");
		if (printBinInfoEnabled) str.write("//global count: " + script.getGlobalCount() + "\r\n");
		//Local variables
		ListIterator<String> varIt = script.getVariables().listIterator(script.getParameterCount());
		String name0 = null;
		int size = 1;
		while (varIt.hasNext()) {
			String name = varIt.next();
			if ("LHVMA".equals(name)) {	//Variable array hack (CI)
				size++;
			} else {
				if (name0 != null) {
					if (size > 1) {
						str.write("["+size+"]");
						size = 1;
					}
				}
				name0 = name;
				str.write("\r\n\tlocal "+name);
			}
		}
		if (name0 != null) {
			if (size > 1) {
				str.write("["+size+"]");
			}
		}
		str.write("\r\n");
		//Code
		if (printBinInfoEnabled) str.write("//instruction address: 0x" + Integer.toHexString(firstInstruction) + "\r\n");
		int index = firstInstruction;
		ListIterator<Instruction> it = instructions.listIterator(index);
		boolean endFound = false;
		int instrAfterEnd = 0;
		int prevSrcLine = instructions.get(index + 1).lineNumber;
		int skipSrcLines = 0;
		do {
			try {
				if (endFound) instrAfterEnd++;
				instr = it.next();
				Label label = labels.get(index);
				if (label != null && label.forwardReferenced) {
					str.write(label.getForwardName() + ":\r\n");
				}
				if (label != null && label.exceptionHandler) {
					str.write(label.getExceptName() + ":\r\n");
				}
				if (printSourceLineEnabled && source != null) {
					if (instr.opcode == OPCode.EXCEPT) {
						if (index > firstInstruction) {
							/*The line number for EXCEPT is the one of the end of block (i.e. "end while"),
							 * this would just cause confusion. We insert an empty comment just to separate
							 * the EXCEPT from the previous instruction. */
							str.write("//\r\n");
						}
					} else if (instr.lineNumber > 0 && instr.lineNumber <= source.length
							&& instr.lineNumber != prevSrcLine
							&& instr.opcode != OPCode.JZ) {
						if (skipSrcLines > 0) {
							skipSrcLines--;
						} else if (instr.opcode == OPCode.BRKEXCEPT) {
							skipSrcLines = 1;
						} else {
							if (printSourceCommentsEnabled) {
								//Search for previous comments
								for (int i = instr.lineNumber - 2; i >= 0; i--) {
									String src = source[i];
									if (src.isBlank() || src.trim().startsWith("//")) {
										comments.push(src);
									} else {
										break;
									}
								}
								//Write previous comments
								while (!comments.isEmpty()) {
									str.write(comments.pop() + "\r\n");
								}
							}
							//Write the statement
							str.write("//@" + source[instr.lineNumber - 1]);	//line numbers start from 1
							str.write("\t\t//#" + script.getSourceFilename() + ":" + instr.lineNumber + "\r\n");
							prevSrcLine = instr.lineNumber;
						}
					} else if (instr.isFree()) {
						str.write("//@\tstart\r\n");
					}
				}
				if (label != null && label.backReferenced) {
					str.write(label.getBackName() + ":\r\n");
				}
				str.write("\t" + instr.toString(chl, script, labels));
				boolean isConstRef = instr.opcode == OPCode.PUSH && !instr.isReference() && instr.dataType == DataType.INT;
				if (printDataHintEnabled && isConstRef && instr.intVal > 0 && constMap.containsKey(instr.intVal)) {
					str.write("\t//" + constMap.get(instr.intVal));
				} else if (printNativeInfoEnabled && instr.opcode == OPCode.SYS) {
					NativeFunction f = NativeFunction.fromCode(instr.intVal);
					str.write("\t//" + f.getInfoString());
				/*} else if (instr.opcode == OPCode.PUSH && !instr.isReference() && instr.dataType == DataType.VAR) {
					String varName = getGlobalVar(chl, script, instr.intVal);
					str.write("\t//" + varName);*/
				} else if (printBinInfoEnabled) {
					if (instr.opcode.isIP) {
						str.write(String.format("\t//offset: 0x%X, target: 0x%X", index, instr.intVal));
					} else {
						str.write(String.format("\t//offset: 0x%X", index));
					}
				}
				if (printSourceLinenoEnabled
						/*&& instr.lineNumber > 0
						&& instr.lineNumber != prevSrcLine
						&& instr.opcode != OPCode.JZ && instr.opcode != OPCode.EXCEPT*/) {
					str.write("\t\t//#" + script.getSourceFilename() + ":" + instr.lineNumber);
					prevSrcLine = instr.lineNumber;
				}
				if (instr.opcode == OPCode.END) {
					endFound = true;
					str.write("\t//"+script.getName());
				}
				str.write("\r\n");
				index++;
			} catch (Exception e) {
				throw new CompileException(script.getName(), script.getSourceFilename(), index, e);
			}
		} while (it.hasNext() && index <= script.getLastInstructionAddress());
		if (instrAfterEnd > 0) {
			out.println(instrAfterEnd + " instructions found after end of script " + script.getName());
		}
	}
	
	private void writeAutoStartScripts(CHLFile chl, Writer str) throws IOException, CompileException {
		str.write(".AUTORUN\r\n");
		for (int scriptID : chl.autoStartScripts.getScripts()) {
			try {
				Script script = chl.scripts.getScript(scriptID);
				str.write("run script "+script.getName()+"\r\n");
			} catch (InvalidScriptIdException e) {
				String msg = "Invalid autorun script id: " + scriptID;
				str.write("//" + msg + "\r\n");
				throw new CompileException(msg);
			}
		}
		str.write("\r\n");
	}
	
	private void setSourceFile(String sourceFilename) {
		sourceFilename = sourceFilename.stripTrailing();
		if (!sourceFilename.equals(currentSourceFilename)) {
			//Path file = sourcePath.resolve(sourceFilename);
			File file = find(sourcePath.toFile(), sourceFilename);
			if (file != null) {
				try {
					List<String> lines = Files.readAllLines(file.toPath(), SRC_CHARSET);
					source = lines.toArray(new String[0]);
				} catch (IOException e) {
					source = null;
					out.println("WARNING: failed to read source file '" + sourceFilename + "': " + e);
				}
			} else {
				source = null;
			}
			currentSourceFilename = sourceFilename;
		}
	}
	
	
	private static class Label implements ILabel {
		public final String scriptName;
		public final int id;
		/**Tells whether this label is referenced by a previous instruction.*/
		public boolean backReferenced;
		/**Tells whether this label is referenced by a subsequent instruction.*/
		public boolean forwardReferenced;
		/**Tells whether this label is referenced by an EXCEPT instruction.*/
		public boolean exceptionHandler;
		
		public Label(String scriptName, int uniqueId) {
			this.scriptName = scriptName;
			this.id = uniqueId;
		}
		
		public String getBackName() {
			return scriptName + "_loop_" + id;
		}
		
		public String getForwardName() {
			return scriptName + "_skip_" + id;
		}
		
		public String getExceptName() {
			return scriptName + "_exception_handler_" + id;
		}
		
		@Override
		public String toString(Instruction instruction) {
			if (instruction.opcode == OPCode.EXCEPT) {
				return getExceptName();
			} else if (instruction.isForward()) {
				return getForwardName();
			} else {
				return getBackName();
			}
		}
		
		@Override
		public String toString() {
			return "lbl_" + id;
		}
	}
}
