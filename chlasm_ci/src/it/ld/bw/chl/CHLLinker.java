package it.ld.bw.chl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import it.ld.bw.chl.exceptions.LinkError;
import it.ld.bw.chl.exceptions.ScriptNotFoundException;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.DataSection.StringData;
import it.ld.bw.chl.model.DataType;
import it.ld.bw.chl.model.Header;
import it.ld.bw.chl.model.InitGlobal;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.OPCode;
import it.ld.bw.chl.model.OPCodeMode;
import it.ld.bw.chl.model.ObjectCode;
import it.ld.bw.chl.model.Script;
import it.ld.bw.chl.model.Scripts;

public class CHLLinker {
	public static boolean traceEnabled = false;
	
	protected static final Charset ASCII = Charset.forName("windows-1252");
	
	private PrintStream out;
	private Options options = new Options();
	
	public CHLLinker(PrintStream outStream) {
		this.out = outStream;
	}
	
	public Options getOptions() {
		return options;
	}
	
	public void setOptions(Options options) {
		this.options = options;
	}
	
	private void info(String s) {
		if (options.verbose) {
			out.println(s);
		}
	}
	
	public CHLFile link(List<File> files) throws LinkError, IOException {
		CHLFile chl = new CHLFile();
		chl.header.setVersion(Header.BWCI);
		//Read object code and compute required space
		int globalCount = 0;
		int scriptsCount = 0;
		int codeSize = 0;
		int dataSize = options.debug ? 2048 : 0;
		int autostartCount = 0;
		List<ObjectCode> objs = new ArrayList<>(files.size());
		for (File file : files) {
			try {
				info("Loading " + file.getName());
				ObjectCode objcode = new ObjectCode();
				objcode.read(file);
				CHLFile srcChl = objcode.getChl();
				globalCount += srcChl.globalVars.getNames().size();
				scriptsCount += srcChl.scripts.getItems().size();
				codeSize += srcChl.code.getItems().size();
				dataSize += srcChl.data.getData().length;
				autostartCount += srcChl.autoStartScripts.getScripts().size();
				objs.add(objcode);
			} catch (Exception e) {
				throw new LinkError(e, file);
			}
		}
		//Allocate buffers
		ArrayList<String> globalVars = new ArrayList<>(globalCount);
		chl.globalVars.setNames(globalVars);
		ArrayList<InitGlobal> initGlobals = new ArrayList<>(globalCount + 1);
		initGlobals.add(new InitGlobal("Null variable", 0));
		chl.initGlobals.setItems(initGlobals);
		ArrayList<Script> scripts = new ArrayList<>(scriptsCount);
		chl.scripts.setItems(scripts);
		ArrayList<Instruction> instructions = new ArrayList<>(codeSize);
		chl.code.setItems(instructions);
		ByteArrayOutputStream data = new ByteArrayOutputStream(dataSize);
		LinkedHashMap<String, Integer> stringMap = new LinkedHashMap<>();
		ArrayList<Integer> autostartScripts = new ArrayList<>(autostartCount);
		chl.autoStartScripts.setScripts(autostartScripts);
		Set<String> properties = new HashSet<>();
		Set<String> sourceDirs = new HashSet<>();
		List<Integer> stringInstructions = new LinkedList<>();
		//
		info("Linking...");
		for (ObjectCode objcode : objs) {
			CHLFile srcChl = objcode.getChl();
			HashSet<Integer> srcStrInstr = new HashSet<>(objcode.getStringInstructions());
			//Add data
			final int dataOffset = data.size();
			for (StringData sData : srcChl.data.getStrings()) {
				String str = sData.getString();
				if (str.startsWith("crc32[")) {
					properties.add(str);
				} else if (str.startsWith("source_dirs=")) {
					String[] vals = str.split("=", 2)[1].split(";");
					for (String val : vals) {
						sourceDirs.add(val);
					}
				} else if (!options.sharedStrings) {
					byte[] bytes = sData.getBytes();
					data.write(bytes);
				} else if (!stringMap.containsKey(str)) {
					byte[] bytes = sData.getBytes();
					stringMap.put(str, data.size());
					data.write(bytes);
				}
			}
			//Create mapping for external vars
			int[] externalVarsMap = new int[objcode.getExternalVars().size()];
			for (Entry<String, Integer> entry : objcode.getExternalVars().entrySet()) {
				String[] tks = entry.getKey().split("\\+");
				String name = tks[0];
				int index = Integer.valueOf(tks[1]);
				int varId = chl.globalVars.getVarId(name);
				if (varId < 0) {
					throw new LinkError("Cannot find external variable " + name, objcode.file);
				} else if (index > 0 && !chl.globalVars.isArray(varId)) {
					throw new LinkError("External variable " + name + " isn't an array", objcode.file);
				} else if (index >= chl.globalVars.getVarSize(varId)) {
					throw new LinkError("Index out of bounds for external variable " + name, objcode.file);
				}
				final int srcId = entry.getValue();
				externalVarsMap[srcId - 1] = varId + index;
			}
			//Add global vars
			final int globalOffset = globalVars.size();
			globalVars.addAll(srcChl.globalVars.getNames());
			List<InitGlobal> srcInits = srcChl.initGlobals.getItems();
			initGlobals.addAll(srcInits.subList(1, srcInits.size()));
			final int globalsCount = globalVars.size();
			//Create mapping for external scripts
			int[] externalScriptsMap = new int[objcode.getExternalScripts().size()];
			for (Entry<String, Integer> entry : objcode.getExternalScripts().entrySet()) {
				String[] tks = entry.getKey().split("@");
				String name = tks[0];
				int argc = Integer.valueOf(tks[1]);
				try {
					Script script = chl.scripts.getScript(name);
					if (script.getParameterCount() != argc) {
						throw new LinkError("Wrong number of parameters for external script " + name, objcode.file);
					}
					final int srcId = entry.getValue();
					externalScriptsMap[srcId - 1] = script.getScriptID();
				} catch (ScriptNotFoundException e) {
					throw new LinkError("Cannot find external script " + name, objcode.file);
				}
			}
			//Add and create mapping for internal scripts (this invalidates source scripts)
			Scripts scriptsSection = srcChl.scripts;
			int[] internalScriptsMap = new int[scriptsSection.getItems().size()];
			for (Script script : scriptsSection.getItems()) {
				final int oldId = script.getScriptID();
				final int newId = scripts.size() + 1;
				script.setScriptID(newId);
				scripts.add(script);
				internalScriptsMap[oldId - 1] = newId;
			}
			//Add and relocate code (this invalidates source instructions)
			final int baseAddress = instructions.size();
			ArrayList<Instruction> srcInstructions = srcChl.code.getItems();
			for (Script script : scriptsSection.getItems()) {
				final int newScriptAddress = instructions.size();
				final int localsDelta = globalsCount - script.getGlobalCount();
				for (int i = script.getInstructionAddress(); i <= srcInstructions.size(); i++) {
					Instruction instr = srcInstructions.get(i);
					OPCode opcode = instr.opcode;
					boolean popNull = opcode == OPCode.POP && instr.intVal == 0;
					if (opcode.hasArg && !popNull) {
						if (instr.opcode.isIP) {
							instr.intVal += baseAddress;
						} else if (instr.opcode.isScript) {
							if (instr.intVal >= 0) {
								instr.intVal = internalScriptsMap[instr.intVal - 1];
							} else {
								instr.intVal = externalScriptsMap[-instr.intVal - 1];
							}
						} else if (instr.isReference() || instr.dataType == DataType.VAR) {
							if (instr.intVal > script.getGlobalCount()) {	//Local vars
								instr.intVal += localsDelta;
							} else if (instr.intVal >= 0) {					//Internal global vars
								instr.intVal += globalOffset;
							} else {										//External global vars
								instr.intVal = externalVarsMap[-instr.intVal - 1];
							}
						} else if (instr.opcode == OPCode.PUSH && instr.dataType == DataType.INT) {
							if (srcStrInstr.contains(i)) {
								if (options.sharedStrings) {
									String str = srcChl.data.getString(instr.intVal);
									instr.intVal = stringMap.get(str);
								} else {
									instr.intVal += dataOffset;
								}
								stringInstructions.add(instructions.size());
							}
						}
					} else if (opcode == OPCode.REF_PUSH && instr.mode == OPCodeMode.REF) {
						if (i < 2) {
							throw new LinkError("Missing instructions before REF_PUSH2", objcode.file);
						}
						Instruction instr2 = srcInstructions.get(i - 2);
						if (instr2.opcode != OPCode.PUSH || instr2.dataType != DataType.FLOAT || instr2.mode != 1) {
							throw new LinkError("Expected PUSHF 2 lines before REF_PUSH2", objcode.file);
						}
						int intVal = (int)instr2.floatVal;
						if (intVal > script.getGlobalCount()) {	//Local vars
							intVal += localsDelta;
						} else if (intVal >= 0) {				//Internal global vars
							intVal += globalOffset;
						} else {								//External global vars
							intVal = externalVarsMap[-intVal - 1];
						}
						instr2.floatVal = intVal;
					}
					instructions.add(instr);
					if (instr.opcode == OPCode.END) break;
				}
				script.setChl(chl);
				script.setInstructionAddress(newScriptAddress);
				script.setGlobalCount(script.getGlobalCount() + localsDelta);
			}
			//Add autostart scripts
			for (Integer srcId : srcChl.autoStartScripts.getScripts()) {
				int id = srcId >= 0 ? internalScriptsMap[srcId - 1] : externalScriptsMap[-srcId - 1];
				autostartScripts.add(id);
			}
		}
		if (options.debug) {
			for (String s : properties) {
				data.write(s.getBytes(ASCII));
				data.write((byte)0);
			}
			if (!sourceDirs.isEmpty()) {
				String s = "source_dirs=" + String.join(";", sourceDirs.toArray(new String[0]));
				data.write(s.getBytes(ASCII));
				data.write((byte)0);
			}
			StringBuffer buf = new StringBuffer(20 + stringInstructions.size() * 5);
			buf.append("string_instructions=");
			if (!stringInstructions.isEmpty()) {
				Iterator<Integer> it = stringInstructions.iterator();
				int instr = it.next();
				buf.append(String.valueOf(instr));
				while (it.hasNext()) {
					instr = it.next();
					buf.append(",");
					buf.append(String.valueOf(instr));
				}
			}
			data.write(buf.toString().getBytes(ASCII));
			data.write((byte)0);
		}
		//Copy data buffer to data section
		chl.data.setData(data.toByteArray());
		//
		return chl;
	}
	
	
	public static class Options {
		public boolean sharedStrings = true;
		public boolean debug = false;
		public boolean verbose = false;
	}
}
