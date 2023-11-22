package it.ld.bw.chl.lang;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import it.ld.bw.chl.exceptions.DecompileException;
import it.ld.bw.chl.exceptions.InvalidNativeFunctionException;
import it.ld.bw.chl.exceptions.InvalidScriptIdException;
import it.ld.bw.chl.exceptions.InvalidVariableIdException;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.DataType;
import it.ld.bw.chl.model.InitGlobal;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.NativeFunction;
import it.ld.bw.chl.model.NativeFunction.ArgType;
import it.ld.bw.chl.model.OPCode;
import it.ld.bw.chl.model.OPCodeFlag;
import it.ld.bw.chl.model.Script;

public class CHLDecompiler {
	private static final char[] ILLEGAL_CHARACTERS = {'/', '\n', '\r', '\t', '\0', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':'};
	
	private static final Expression END_SCRIPT = new Expression("end script");
	private static final Expression SELF_ASSIGN = new Expression("?");
	
	private static final ArgType[] typeMap = new ArgType[] {
			ArgType.UNKNOWN,
			ArgType.INT,
			ArgType.FLOAT,
			ArgType.COORD,
			ArgType.OBJECT_OBJ,
			ArgType.UNKNOWN,
			ArgType.BOOL,
			ArgType.UNKNOWN
		};
	
	private final Map<String, Var> globalMap = new HashMap<>();
	private final Map<String, Var> localMap = new HashMap<>();
	private final ArrayList<ArgType> stack = new ArrayList<>();
	private List<Instruction> instructions;
	private ListIterator<Instruction> it;
	private Script currentScript;
	
	private String tabs = "";
	
	private CHLFile chl;
	private Path path;
	
	private PrintStream out;
	
	public CHLDecompiler() {
		this(System.out);
	}
	
	public CHLDecompiler(PrintStream out) {
		this.out = out;
	}
	
	public void decompile(CHLFile chl, File outdir) throws IOException, DecompileException {
		this.chl = chl;
		this.path = outdir.toPath();
		globalMap.clear();
		stack.clear();
		instructions = chl.getCode().getItems();
		currentScript = null;
		mapGlobalVars();
		//
		List<String> sources = chl.getSourceFilenames();
		out.println("Writing _challenges.txt");
		File listFile = path.resolve("_challenges.txt").toFile();
		try (FileWriter str = new FileWriter(listFile);) {
			str.write("//Source files list\r\n");
			for (String sourceFilename : sources) {
				if (!isValidFilename(sourceFilename)) {
					throw new RuntimeException("Invalid source filename: " + sourceFilename);
				}
				str.write(sourceFilename + "\r\n");
			}
			str.write("_autorun.txt\r\n");
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
				writeScripts(chl, str, sourceFilename);
			}
		}
	}
	
	private void mapGlobalVars() {
		List<InitGlobal> initGlobals = chl.getInitGlobals().getItems();
		Map<String, Float> initMap = new HashMap<>();
		for (InitGlobal init : initGlobals) {
			String name = init.getName();
			if (!"LHVMA".equals(name)) {
				initMap.put(name, init.getFloat());
			}
		}
		//
		List<String> globalVars = chl.getGlobalVariables().getNames();
		Var var = null;
		int index = 0;
		for (String name : globalVars) {
			index++;
			if ("LHVMA".equals(name)) {
				var.size++;
			} else {
				float val = initMap.getOrDefault(name, 0f);
				var = new Var(name, index, 1, val);
				globalMap.put(name, var);
			}
		}
	}
	
	private void writeHeader(CHLFile chl, Writer str) throws IOException {
		str.write("//LHVM Challenge source version "+chl.getHeader().getVersion()+"\r\n");
		str.write("//Decompiled with CHLASM tool by Daniels118\r\n");
		str.write("\r\n");
	}
	
	private void writeAutoStartScripts(CHLFile chl, Writer str) throws IOException, DecompileException {
		for (int scriptID : chl.getAutoStartScripts().getScripts()) {
			try {
				Script script = chl.getScriptsSection().getScript(scriptID);
				str.write("run script "+script.getName()+"\r\n");
			} catch (InvalidScriptIdException e) {
				String msg = "Invalid autorun script id: " + scriptID;
				str.write("//" + msg + "\r\n");
				throw new DecompileException(msg);
			}
		}
		str.write("\r\n");
	}
	
	private void writeScripts(CHLFile chl, Writer str, String sourceFilename) throws IOException, DecompileException {
		chl.getScriptsSection().finalizeScripts();	//Required to initialize the last instruction index of each script
		int firstGlobal = 0;
		Script script = null;
		ListIterator<Script> it = chl.getScriptsSection().getItems().listIterator();
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
		while (it.hasNext()) {
			script = it.next();
			if (!script.getSourceFilename().equals(sourceFilename)) {
				break;
			}
			writeScript(chl, str, script);
			str.write("\r\n");
		}
	}
	
	private void writeGlobals(CHLFile chl, Writer str, int start, int end) throws IOException {
		List<String> names = chl.getGlobalVariables().getNames().subList(start, end);
		for (String name : names) {
			if (!"LHVMA".equals(name)) {
				Var var = globalMap.get(name);
				String line = "global "+var.name;
				if (var.size > 1) {
					line += "["+var.size+"]";
				} else if (var.val != 0) {
					line += " = " + var.val;
				}
				str.write(line+"\r\n");
			}
		}
	}
	
	private static List<Var> getLocalVars(Script script) {
		List<Var> res = new ArrayList<>();
		List<String> vars = script.getVariables();
		Var var = null;
		int index = script.getGlobalCount();
		for (String name : vars) {
			index++;
			if ("LHVMA".equals(name)) {
				var.size++;
			} else {
				var = new Var(name, index, 1, 0);
				res.add(var);
			}
		}
		return res;
	}
	
	private void writeScript(CHLFile chl, Writer str, Script script) throws IOException, DecompileException {
		currentScript = script;
		//Load parameters on the stack
		for (int i = 0; i < script.getParameterCount(); i++) {
			push(ArgType.UNKNOWN);
		}
		str.write("begin " + script.getSignature() + "\r\n");
		it = instructions.listIterator(script.getInstructionAddress());
		//EXCEPT
		accept(OPCode.EXCEPT, 1, DataType.INT);
		//Local vars (including parameters)
		List<Var> localVars = getLocalVars(script);
		for (int i = 0; i < localVars.size(); i++) {
			Var var = localVars.get(i);
			localMap.put(var.name, var);
			if (i < script.getParameterCount()) {								//parameter
				accept(OPCode.POP, OPCodeFlag.REF, DataType.FLOAT, var.index);
			} else if (var.isArray()) {											//array
				str.write("\t"+var.name+"["+var.size+"]\r\n");
			} else {															//atomic var
				Expression statement = decompileLocalVarAssignment(var);
				str.write("\t"+statement+"\r\n");
			}
		}
		//START
		accept(OPCode.ENDEXCEPT, OPCodeFlag.FREE, DataType.INT, 0);
		str.write("start\r\n");
		//
		tabs = "\t";
		Expression statement = decompileNextStatement();
		while (statement != END_SCRIPT) {
			if (statement != null) {
				str.write(tabs + statement + "\r\n");
			}
			statement = decompileNextStatement();
		}
		//
		str.write("end script " + script.getName() + "\r\n");
		if (!stack.isEmpty()) {
			out.println("Stack is not empty at end of script "+script.getName()+":");
			out.println(stack);
			out.println();
		}
	}
	
	private Expression decompileLocalVarAssignment(Var var) throws DecompileException {
		Instruction popf = find(OPCode.POP, OPCodeFlag.REF, DataType.FLOAT, var.index);
		if (popf == null) {
			throw new DecompileException("Cannot find variable initialization statement");
		}
		final int pos = it.nextIndex();
		Expression statement = decompile();
		gotoAddress(pos);
		return statement;
	}
	
	private Expression decompileNextStatement() throws DecompileException {
		findEndOfStatement();
		final int pos = it.nextIndex();
		Expression statement = decompile();
		gotoAddress(pos);
		return statement;
	}
	
	private Expression decompile() throws DecompileException {
		Expression op1, op2;
		String varName, varIndex;
		Instruction pInstr;
		Instruction instr = prev();
		switch (instr.opcode) {
			case ADD:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					if (op2.isOne()) {
						return new Expression("++");
					} else {
						return new Expression(" += " + op2);
					}
				} else {
					return new Expression(op1 + " + " + op2, true);
				}
			case SUB:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					if (op2.isOne()) {
						return new Expression("--");
					} else {
						return new Expression(" -= " + op2);
					}
				} else {
					return new Expression(op1 + " - " + op2, true);
				}
			case MUL:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					return new Expression(" *= " + op2);
				} else {
					return new Expression(op1.safe() + " * " + op2.safe());
				}
			case MOD:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1.safe() + " % " + op2.safe());
			case DIV:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					return new Expression(" /= " + op2);
				} else {
					return new Expression(op1.safe() + " / " + op2.safe());
				}
			case AND:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1.safe() + " and " + op2.safe());
			case OR:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " or " + op2, true);
			case NOT:
				op1 = decompile();
				return new Expression("not " + op1.safe());
			case CAST:
				op1 = decompile();
				switch (instr.dataType) {
					case FLOAT:
						return new Expression("variable " + op1.safe());
					case INT:
						return new Expression("constant " + op1.safe());
					default:
				}
				break;
			case SYS:
				try {
					NativeFunction func = NativeFunction.fromCode(instr.intVal);
					return decompile(func);
				} catch (InvalidNativeFunctionException e) {
					throw new DecompileException(currentScript.getName(), it.previousIndex(), e);
				}
			case CALL:
				try {
					Script script = chl.getScriptsSection().getScript(instr.intVal);
					String line = "run";
					if (instr.isStart()) {
						line += " background";
					}
					line += " script " + script.getName();
					if (script.getParameterCount() > 0) {
						String params = decompile().toString();
						for (int i = 1; i < script.getParameterCount(); i++) {
							params = decompile() + ", " + params;
						}
						line += "(" + params + ")";
					}
					return new Expression(line);
				} catch (InvalidScriptIdException e) {
					throw new DecompileException(currentScript.getName(), it.previousIndex(), e);
				}
			case EQ:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " == " + op2);
			case GEQ:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " >= " + op2);
			case GT:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " > " + op2);
			case LEQ:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " <= " + op2);
			case LT:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " < " + op2);
			case NEG:
				op1 = decompile();
				return new Expression("-" + op1.safe());
			case NEQ:
				op2 = decompile();
				op1 = decompile();
				return new Expression(op1 + " != " + op2);
			case JMP:
				return null;
			case JZ:
				
				break;
			case POP:
				if (instr.isReference()) {
					String var = getVar(instr.intVal);
					return new Expression(var + " = " + decompile());
				} else {
					return decompile();
				}
			case PUSH:
				if (instr.isReference()) {
					String var = getVar(instr.intVal);
					return new Expression(var);
				} else {
					switch (instr.dataType) {
						case FLOAT:
							return new Expression(String.valueOf(instr.floatVal));
						case INT:
							return new Expression(String.valueOf(instr.intVal));
						case VAR:
							return new Expression(getVar(instr.intVal, false));
						default:
					}
				}
			case REF_PUSH:
				verify(it.nextIndex(), instr, OPCode.REF_PUSH, OPCodeFlag.REF, DataType.VAR);
				pInstr = prev();	//ADDF
				verify(it.nextIndex(), pInstr, OPCode.ADD, 1, DataType.FLOAT);
				pInstr = prev();	//PUSHF var
				verify(it.nextIndex(), pInstr, OPCode.PUSH, 1, DataType.VAR);
				varName = getVar((int)pInstr.floatVal, false);
				varIndex = decompile().toString();
				return new Expression(varName + "[" + varIndex + "]");
			case REF_ADD_PUSH:
				
				break;
			case REF_AND_OFFSET_PUSH:
				next();
				return SELF_ASSIGN;
			case REF_AND_OFFSET_POP:
				op2 = decompile();
				pInstr = peek(-1);
				if (pInstr.opcode == OPCode.POP && pInstr.flags == 1) {
					pInstr = prev();	//POPI
					verify(it.nextIndex(), pInstr, OPCode.POP, 1, DataType.INT);
					pInstr = prev();	//REF_AND_OFFSET_PUSH
					verify(it.nextIndex(), pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeFlag.REF, DataType.FLOAT);
					pInstr = prev();	//PUSHV var
					verify(it.nextIndex(), pInstr, OPCode.PUSH, 1, DataType.VAR);
					varName = getVar(pInstr.intVal, false);
					varIndex = decompile().toString();
					Var var = getVar(varName);
					String assignee = varName;
					if (var.isArray()) {
						assignee += "[" + varIndex + "]";
					}
					return new Expression(assignee + " = " + op2);
				} else if (pInstr.opcode == OPCode.REF_AND_OFFSET_PUSH && pInstr.flags == OPCodeFlag.REF) {
					pInstr = prev();	//REF_AND_OFFSET_PUSH
					verify(it.nextIndex(), pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeFlag.REF, DataType.FLOAT);
					pInstr = prev();	//PUSHV var
					verify(it.nextIndex(), pInstr, OPCode.PUSH, 1, DataType.VAR);
					varName = getVar(pInstr.intVal, false);
					varIndex = decompile().toString();
					Var var = getVar(varName);
					String assignee = varName;
					if (var.isArray()) {
						assignee += "[" + varIndex + "]";
					}
					return new Expression(assignee + op2);
				} else {
					throw new DecompileException("Unexpected instruction "+instr+". Expected: POPI|REF_AND_OFFSET_PUSH", currentScript.getName(), it.nextIndex());
				}
			case EXCEPT:
				
				break;
			case BRKEXCEPT:
				
				break;
			case ENDEXCEPT:
				return null;
			case ITEREXCEPT:
				return null;
			case RETEXCEPT:
				
				break;
			case SWAP:
				
				break;
			case DUP:
				
				break;
			case SLEEP:
				op1 = decompile();
				return new Expression(op1.safe() + " seconds");
			case SQRT:
				op1 = decompile();
				return new Expression("square root " + op1.safe());
			case TAN:
				op1 = decompile();
				return new Expression("tan " + op1.safe());
			case SIN:
				op1 = decompile();
				return new Expression("sin " + op1.safe());
			case COS:
				op1 = decompile();
				return new Expression("cos " + op1.safe());
			case ATAN:
				op1 = decompile();
				return new Expression("arctan " + op1.safe());
			case ASIN:
				op1 = decompile();
				return new Expression("arcsin " + op1.safe());
			case ACOS:
				op1 = decompile();
				return new Expression("arccos " + op1.safe());
			case ATAN2:
				//TODO ATAN2
				break;
			case ABS:
				op1 = decompile();
				return new Expression("abs " + op1.safe());
			case LINE:
				break;
			case END:
				return END_SCRIPT;
		}
		throw new DecompileException(instr+" is not supported", currentScript.getName(), it.nextIndex());
	}
	
	private Expression decompile(NativeFunction func) throws DecompileException {
		
		throw new DecompileException(func+" is not supported", currentScript.getName(), it.nextIndex());
	}
	
	private Instruction next() throws DecompileException {
		if (!it.hasNext()) {
			throw new DecompileException("No more instructions", currentScript.getName(), it.previousIndex());
		}
		Instruction instr = it.next();
		stackDo(instr);
		return instr;
	}
	
	private Instruction prev() throws DecompileException {
		Instruction instr = it.previous();
		stackUndo(instr);
		return instr;
	}
	
	private void stackDo(Instruction instr) throws DecompileException {
		if (instr.opcode == OPCode.SYS) {
			try {
				NativeFunction func = NativeFunction.fromCode(instr.intVal);
				if (func.varargs) {
					Instruction pushArgc = peek(-1);
					verify(it.previousIndex() - 1, pushArgc, OPCode.PUSH, 1, DataType.INT);
					int argc = pushArgc.intVal;
					pop();	//argc
					for (int i = 0; i < argc; i++) {
						pop();	//argv
					}
				}
				for (int i = func.args.length - 1; i >= 0; i--) {
					ArgType type = func.args[i].type;
					for (int j = 0; j < type.stackCount; j++) {
						pop();
					}
				}
				if (func.returnType != null) {
					for (int j = 0; j < func.returnType.stackCount; j++) {
						push(func.returnType);
					}
				}
			} catch (InvalidNativeFunctionException e) {
				throw new DecompileException(currentScript.getName(), it.previousIndex(), e);
			}
		} else if (instr.opcode == OPCode.CALL) {
			try {
				Script script = chl.getScriptsSection().getScript(instr.intVal);
				for (int i = 0; i < script.getParameterCount(); i++) {
					pop();
				}
			} catch (InvalidScriptIdException e) {
				throw new DecompileException(currentScript.getName(), it.previousIndex(), e);
			}
		} else {
			for (int i = 0; i < instr.opcode.pop; i++) {
				pop();
			}
			for (int i = 0; i < instr.opcode.push; i++) {
				ArgType type = typeMap[instr.dataType.ordinal()];
				push(type);
			}
		}
	}
	
	private void stackUndo(Instruction instr) throws DecompileException {
		if (instr.opcode == OPCode.SYS) {
			try {
				NativeFunction func = NativeFunction.fromCode(instr.intVal);
				if (func.returnType != null) {
					for (int j = 0; j < func.returnType.stackCount; j++) {
						pop();
					}
				}
				if (func.varargs) {
					Instruction pushArgc = peek(-1);
					verify(it.previousIndex() - 1, pushArgc, OPCode.PUSH, 1, DataType.INT);
					int argc = pushArgc.intVal;
					stack.add(ArgType.INT);	//argc
					for (int i = 0; i < argc; i++) {
						push(ArgType.UNKNOWN);	//argv
					}
				}
				for (int i = func.args.length - 1; i >= 0; i--) {
					ArgType type = func.args[i].type;
					for (int j = 0; j < type.stackCount; j++) {
						push(type);
					}
				}
			} catch (InvalidNativeFunctionException e) {
				throw new DecompileException(currentScript.getName(), it.previousIndex(), e);
			}
		} else if (instr.opcode == OPCode.CALL) {
			try {
				Script script = chl.getScriptsSection().getScript(instr.intVal);
				for (int i = 0; i < script.getParameterCount(); i++) {
					push(ArgType.UNKNOWN);
				}
			} catch (InvalidScriptIdException e) {
				throw new DecompileException(currentScript.getName(), it.previousIndex(), e);
			}
		} else {
			for (int i = 0; i < instr.opcode.push; i++) {
				pop();
			}
			for (int i = 0; i < instr.opcode.pop; i++) {
				ArgType type = typeMap[instr.dataType.ordinal()];
				push(type);
			}
		}
	}
	
	private Instruction peek() throws DecompileException {
		return peek(0);
	}
	
	private Instruction peek(int offset) throws DecompileException {
		int index = it.nextIndex() + offset;
		if (index < currentScript.getInstructionAddress() || index > currentScript.getLastInstructionAddress()) {
			throw new DecompileException("Instruction address "+index+" is invalid", currentScript.getName(), it.previousIndex());
		}
		return instructions.get(index);
	}
	
	private Instruction accept(OPCode opcode, int flags, DataType type) throws DecompileException {
		Instruction instr = next();
		verify(it.previousIndex(), instr, opcode, flags, type);
		return instr;
	}
	
	private Instruction accept(OPCode opcode, int flags, DataType type, int arg) throws DecompileException {
		Instruction instr = next();
		if (instr.opcode != opcode && instr.flags != flags && instr.dataType != type && instr.intVal != arg) {
			throw new DecompileException("Unexpected instruction: "+instr+". Expected "+opcode, currentScript.getName(), it.previousIndex());
		}
		return instr;
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type) throws DecompileException {
		if (instr.opcode != opcode && instr.flags != flags && instr.dataType != type) {
			throw new DecompileException("Unexpected instruction: "+instr+". Expected "+opcode, currentScript.getName(), index);
		}
	}
	
	private Instruction findEndOfStatement() throws DecompileException {
		Instruction instr = next();
		while (!stack.isEmpty()) {
			instr = next();
		}
		return instr;
	}
	
	private Instruction find(OPCode opcode, int flags, DataType type, int arg) throws DecompileException {
		Instruction instr = next();
		while (instr.opcode != opcode || instr.flags != flags || instr.dataType != type || instr.intVal != arg) {
			if (instr.opcode == OPCode.END) {
				return null;
			}
			instr = next();
		}
		return instr;
	}
	
	private void gotoAddress(int index) throws DecompileException {
		while (it.nextIndex() > index) {
			prev();
		}
		while (it.nextIndex() < index) {
			next();
		}
	}
	
	private void push(ArgType type) {
		stack.add(type);
	}
	
	private void pop() {
		stack.remove(stack.size() - 1);
	}
	
	private void incTabs() {
		tabs += "\t";
	}
	
	private void decTabs() {
		tabs = tabs.substring(0, tabs.length() - 1);
	}
	
	private String getVar(int id) throws InvalidVariableIdException {
		return getVar(id, true);
	}
	
	private String getVar(int id, boolean withIndex) throws InvalidVariableIdException {
		List<String> names;
		if (id > currentScript.getGlobalCount()) {
			id -= currentScript.getGlobalCount() + 1;
			names = currentScript.getVariables();
		} else {
			id--;
			names = chl.getGlobalVariables().getNames();
		}
		if (id < 0 || id >= names.size()) {
			throw new InvalidVariableIdException(id);
		}
		String name = names.get(id);
		if ("LHVMA".equals(name)) {
			int index = 0;
			do {
				id--;
				index++;
				name = names.get(id);
			} while ("LHVMA".equals(name));
			return name+"["+index+"]";
		} else {
			id++;
			if (withIndex && id < names.size() && "LHVMA".equals(names.get(id))) {
				return name+"[0]";
			} else {
				return name;
			}
		}
	}
	
	private Var getVar(String name) {
		Var var = localMap.get(name);
		if (var == null) {
			var = globalMap.get(name);
		}
		return var;
	}
	
	private static boolean isValidFilename(String s) {
		for (char c : ILLEGAL_CHARACTERS) {
			if (s.indexOf(c) >= 0) return false;
		}
		return true;
	}
	
	
	private static class Var {
		public final String name;
		public final int index;
		public int size;		//CI introduced arrays
		public final float val;	//CI introduced default value
		
		public Var(String name, int index, int size, float val) {
			if (size <= 0) throw new IllegalArgumentException("Invalid variable size: "+size);
			this.name = name;
			this.index = index;
			this.size = size;
			this.val = val;
		}
		
		public boolean isArray() {
			return size > 1;
		}
		
		@Override
		public String toString() {
			return name;
		}
	}
	
	private static class Expression {
		private final String value;
		public final boolean lowPriority;
		
		public Expression(String value) {
			this(value, false);
		}
		
		public Expression(String value, boolean lowPriority) {
			this.value = value;
			this.lowPriority = lowPriority;
		}
		
		public boolean isOne() {
			return "1.0".equals(value);
		}
		
		public String safe() {
			return lowPriority ? "("+value+")" : value;
		}
		
		@Override
		public String toString() {
			return value;
		}
	}
}
