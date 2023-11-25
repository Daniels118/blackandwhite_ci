package it.ld.bw.chl.lang;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.ld.bw.chl.exceptions.DecompileException;
import it.ld.bw.chl.exceptions.InvalidNativeFunctionException;
import it.ld.bw.chl.exceptions.InvalidScriptIdException;
import it.ld.bw.chl.exceptions.InvalidVariableIdException;
import it.ld.bw.chl.exceptions.ParseException;
import it.ld.bw.chl.lang.Symbol.TerminalType;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.DataType;
import it.ld.bw.chl.model.InitGlobal;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.NativeFunction;
import it.ld.bw.chl.model.NativeFunction.ArgType;
import it.ld.bw.chl.model.NativeFunction.Argument;
import it.ld.bw.chl.model.OPCode;
import it.ld.bw.chl.model.OPCodeFlag;
import it.ld.bw.chl.model.Script;

public class CHLDecompiler {
	public static boolean traceEnabled = false;
	
	private static final String STATEMENTS_FILE = "statements.txt";
	private static final String SUBTYPES_FILE = "subtypes.txt";
	
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
	
	private static final Symbol[] statements = new Symbol[NativeFunction.values().length];
	private static final Map<String, String> subtypes = new HashMap<>();
	private static final Map<String, String[]> boolOptions = new HashMap<>();
	private static final Map<String, String[]> enumOptions = new HashMap<>();
	private static final Map<String, String> dummyOptions = new HashMap<>();
	
	static {
		loadStatements();
		loadSubtypes();
		//
		addBoolOption("enable", "disable");
		addBoolOption("forward", "reverse");
		addBoolOption("open", "close");
		addBoolOption("pause", "unpause");
		addBoolOption("quest", "challenge");
		addBoolOption("enter", "exit");
		addBoolOption("left", "right");
		//
		addEnumOption("HELP_SPIRIT_TYPE", "none", "good", "evil", "last");
		addEnumOption("SAY_MODE", null, "with interaction", "without interaction");
		addEnumOption("SAY_MODE", null, "with interaction", "without interaction");
		addEnumOption("[anti]", null, "anti");
		//
		dummyOptions.put("second|seconds", "seconds");
		dummyOptions.put("event|events", "events");
		dummyOptions.put("graphics|gfx", "graphics");
	}
	
	private static void addBoolOption(String wordTrue, String wordFalse) {
		boolOptions.put(wordTrue + "|" + wordFalse, new String[] {wordTrue, wordFalse});
	}
	
	private static void addEnumOption(String keyword, String...options) {
		enumOptions.put(keyword, options);
	}
	
	private final Map<String, Map<Integer, String>> enums = new HashMap<>();
	private final Map<String, String> aliases = new HashMap<>();
	
	private final Map<String, Var> globalMap = new HashMap<>();
	private final Map<String, Var> localMap = new HashMap<>();
	private final ArrayList<StackVal> stack = new ArrayList<>();
	private final ArrayList<Integer> argcStack = new ArrayList<>();
	private List<Instruction> instructions;
	private ListIterator<Instruction> it;
	private int ip;
	private int lastTracedIp;
	private int nextStatementIndex;
	private Script currentScript;
	private ArrayList<Block> blocks = new ArrayList<>();
	private Block currentBlock = null;
	
	private String tabs = "";
	private boolean incTabs = false;
	
	private CHLFile chl;
	private Path path;
	
	private PrintStream out;
	
	public CHLDecompiler() {
		this(System.out);
	}
	
	public CHLDecompiler(PrintStream out) {
		this.out = out;
	}
	
	public void addHeader(File file) throws FileNotFoundException, IOException, ParseException {
		CHeaderParser parser = new CHeaderParser();
		Map<String, Map<String, Integer>> lEnums = new HashMap<>();
		parser.parse(file, null, lEnums);
		for (Entry<String, Map<String, Integer>> e : lEnums.entrySet()) {
			String enumName = e.getKey();
			Map<String, Integer> enumEntries = e.getValue();
			Map<Integer, String> revEntries = new HashMap<>();
			for (Entry<String, Integer> entry : enumEntries.entrySet()) {
				String entryName = entry.getKey();
				Integer entryVal = entry.getValue();
				revEntries.put(entryVal, entryName);
			}
			enums.put(enumName, revEntries);
		}
	}
	
	public void addAlias(File file) throws FileNotFoundException, IOException, ParseException {
		Pattern pattern = Pattern.compile("global\\s+constant\\s+(.*)\\s*=\\s*(.*)");
	    int lineno = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));) {
			String line = "";
			while ((line = reader.readLine()) != null) {
				line = line.split("//")[0].trim();
				lineno++;
				if (line.isEmpty()) continue;
				Matcher matcher = pattern.matcher(line);
				if (matcher.matches()) {
					String alias = matcher.group(1).trim();
					String symbol = matcher.group(2).trim();
					String oldAlias = aliases.get(symbol);
					if (oldAlias == null) {
						aliases.put(symbol, alias);
					} else if (!alias.equals(oldAlias)) {
						out.println("NOTICE: definition of muliple aliases for symbol "+symbol+" at "+file.getName()+":"+lineno);
					}
				} else {
					throw new ParseException("Expected: global constant ALIAS = CONSTANT", file, lineno);
				}
			}
		}
	}
	
	private String getSymbol(ArgType type, int val) {
		return getSymbol(type, val, true);
	}
	
	private String getSymbol(String type, int val) {
		return getSymbol(type, val, true);
	}
	
	private String getSymbol(ArgType type, int val, boolean useAlias) {
		return getSymbol(type.name(), val, useAlias);
	}
	
	private String getSymbol(String type, int val, boolean useAlias) {
		String r = String.valueOf(val);
		Map<Integer, String> e = enums.get(type);
		if (e != null) {
			String enumEntry = e.get(val);
			if (enumEntry != null) {
				r = enumEntry;
				if (useAlias) {
					String alias = aliases.get(enumEntry);
					if (alias != null) {
						r = alias;
					}
				}
			}
		}
		return r;
	}
	
	private String getEnumEntry(ArgType type, int val) {
		Map<Integer, String> e = enums.get(type.name());
		if (e != null) {
			return e.get(val);
		}
		return null;
	}
	
	public void decompile(CHLFile chl, File outdir) throws IOException, DecompileException {
		this.chl = chl;
		this.path = outdir.toPath();
		globalMap.clear();
		stack.clear();
		instructions = chl.getCode().getItems();
		lastTracedIp = -1;
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
					line += " = " + format(var.val);
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
		trace("begin " + script.getSignature());
		//Load parameters on the stack
		for (int i = 0; i < script.getParameterCount(); i++) {
			push(ArgType.UNKNOWN);
		}
		str.write("begin " + script.getSignature() + "\r\n");
		it = instructions.listIterator(script.getInstructionAddress());
		//EXCEPT
		Instruction except = accept(OPCode.EXCEPT, 1, DataType.INT);
		pushBlock(new Block(ip, BlockType.SCRIPT, except.intVal - 1, except.intVal));
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
		incTabs = false;
		Expression statement = decompileNextStatement();
		while (statement != END_SCRIPT) {
			if (statement != null) {
				str.write(tabs + statement + "\r\n");
			}
			if (incTabs) {
				tabs += "\t";	//Indentation must be delayed by one statement
				incTabs = false;
			}
			statement = decompileNextStatement();
		}
		//
		str.write("end script " + script.getName() + "\r\n");
		trace("end script " + script.getName() + "\r\n");
		if (!stack.isEmpty()) {
			out.println("Stack is not empty at end of script "+script.getName()+":");
			out.println(stack);
			out.println();
		}
		if (!blocks.isEmpty()) {
			out.println("Blocks stack is not empty at end of script "+script.getName()+":");
			out.println();
		}
	}
	
	private Expression decompileLocalVarAssignment(Var var) throws DecompileException {
		int start = ip;
		Instruction popf = find(OPCode.POP, OPCodeFlag.REF, DataType.FLOAT, var.index);
		if (popf == null) {
			throw new DecompileException("Cannot find initialization statement for variable "+var, currentScript.getName(), start);
		}
		nextStatementIndex = ip + 1;
		Expression statement = decompile();
		gotoAddress(nextStatementIndex);
		return statement;
	}
	
	private Expression decompileNextStatement() throws DecompileException {
		findEndOfStatement();
		nextStatementIndex = ip + 1;
		Expression statement = decompile();
		gotoAddress(nextStatementIndex);
		return statement;
	}
	
	private Expression decompile() throws DecompileException {
		Expression op1, op2, op3;
		String varName, varIndex;
		Instruction pInstr, nInstr, nInstr2;
		Instruction instr = prev();
		switch (instr.opcode) {
			case ADD:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					if (op2.floatVal() == 1) {
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
					if (op2.floatVal() == 1) {
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
				switch (instr.dataType) {
					case INT:
						op1 = decompile();
						return new Expression("constant " + op1.safe());
					case FLOAT:
						op1 = decompile();
						return new Expression("variable " + op1.safe());
					case COORDS:
						op3 = decompile();
						pInstr = prev();	//CASTC
						verify(ip, pInstr, OPCode.CAST, 1, DataType.COORDS);
						op2 = decompile();
						pInstr = prev();	//CASTC
						verify(ip, pInstr, OPCode.CAST, 1, DataType.COORDS);
						op1 = decompile();
						return new Expression("[" + op1 + ", " + op2 + ", " + op3 + "]");
					case OBJECT:
						//CASTO
						return decompile();
					default:
				}
				break;
			case SYS:
				try {
					NativeFunction func = NativeFunction.fromCode(instr.intVal);
					if (func == NativeFunction.GET_PROPERTY) {
						pInstr = peek(-1);
						if (pInstr.opcode == OPCode.DUP) {
							//PROPERTY of VARIABLE += EXPRESSION
							next();
							return SELF_ASSIGN;
						} else {
							//PROPERTY of VARIABLE
							op1 = decompile();	//variable
							pInstr = prev();	//PUSHI int
							verify(ip, pInstr, OPCode.PUSH, 1, DataType.INT);
							int propertyId = pInstr.intVal;
							String property = getSymbol(NativeFunction.GET_PROPERTY.args[0].type, propertyId);
							return new Expression(property + " of " + op1);
						}
					} else if (func == NativeFunction.SET_PROPERTY) {
						op2 = decompile();
						pInstr = peek(-1);
						if (pInstr.opcode == OPCode.POP && pInstr.flags == 1) {
							//PROPERTY of VARIABLE = EXPRESSION
							pInstr = prev();	//POPI
							verify(ip, pInstr, OPCode.POP, 1, DataType.INT);
							pInstr = prev();	//SYS2 GET_PROPERTY
							verify(ip, pInstr, NativeFunction.GET_PROPERTY);
							pInstr = prev();	//DUP 1
							verify(ip, pInstr, OPCode.DUP, 0, DataType.NONE, 1);
							pInstr = prev();	//DUP 1
							verify(ip, pInstr, OPCode.DUP, 0, DataType.NONE, 1);
							op1 = decompile();	//variable
							pInstr = prev();	//PUSHI int
							verify(ip, pInstr, OPCode.PUSH, 1, DataType.INT);
							int propertyId = pInstr.intVal;
							String property = getSymbol(NativeFunction.GET_PROPERTY.args[0].type, propertyId);
							//return new Expression(property + " of " + varName + " = " + op2);
							return new Expression(property + " of " + op1 + " = " + op2);
						} else if (pInstr.opcode == OPCode.SYS) {
							//PROPERTY of VARIABLE += EXPRESSION
							pInstr = prev();	//SYS2 GET_PROPERTY
							verify(ip, pInstr, NativeFunction.GET_PROPERTY);
							pInstr = prev();	//DUP 1
							verify(ip, pInstr, OPCode.DUP, 0, DataType.NONE, 1);
							pInstr = prev();	//DUP 1
							verify(ip, pInstr, OPCode.DUP, 0, DataType.NONE, 1);
							op1 = decompile();	//variable
							pInstr = prev();	//PUSHI int
							verify(ip, pInstr, OPCode.PUSH, 1, DataType.INT);
							int propertyId = pInstr.intVal;
							String property = getSymbol(NativeFunction.GET_PROPERTY.args[0].type, propertyId);
							String assignee = property + " of " + op1;
							return new Expression(assignee + op2);
						} else {
							throw new DecompileException("Expected: POPI|SYS2", currentScript.getName(), ip, instr);
						}
					} else {
						return decompile(func);
					}
				} catch (InvalidNativeFunctionException e) {
					throw new DecompileException(currentScript.getName(), ip, e);
				}
			case CALL:
				//run [background] script IDENTIFIER[(parameters)]
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
					throw new DecompileException(currentScript.getName(), ip, e);
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
			case POP:
				if (instr.isReference()) {
					//IDENTIFIER = EXPRESSION
					String var = getVar(instr.intVal);
					return new Expression(var + " = " + decompile());
				} else {
					//statement
					return decompile();
				}
			case PUSH:
				if (instr.isReference()) {
					//IDENTIFIER
					String var = getVar(instr.intVal);
					return new Expression(var);
				} else {
					//NUMBER
					switch (instr.dataType) {
						case FLOAT:
							return new Expression(format(instr.floatVal), instr.dataType);
						case INT:
							return new Expression(String.valueOf(instr.intVal), instr.dataType);
						case BOOLEAN:
							return new Expression(instr.boolVal ? "true" : "false", instr.dataType);
						case VAR:
							return new Expression(getVar(instr.intVal, false));
						default:
					}
				}
			case REF_PUSH:
				//IDENTIFIER\[EXPRESSION\]
				verify(ip, instr, OPCode.REF_PUSH, OPCodeFlag.REF, DataType.VAR);
				pInstr = prev();	//ADDF
				verify(ip, pInstr, OPCode.ADD, 1, DataType.FLOAT);
				pInstr = prev();	//PUSHF var
				verify(ip, pInstr, OPCode.PUSH, 1, DataType.FLOAT);
				varName = getVar((int)pInstr.floatVal, false);
				varIndex = decompile().toString();
				return new Expression(varName + "[" + varIndex + "]");
			case REF_ADD_PUSH:
				break;	//Never found
			case REF_AND_OFFSET_PUSH:
				next();
				return SELF_ASSIGN;
			case REF_AND_OFFSET_POP:
				op2 = decompile();
				pInstr = peek(-1);
				if (pInstr.opcode == OPCode.POP && pInstr.flags == 1) {
					//IDENTIFIER = EXPRESSION
					//IDENTIFIER\[EXPRESSION\] = EXPRESSION
					pInstr = prev();	//POPI
					verify(ip, pInstr, OPCode.POP, 1, DataType.INT);
					pInstr = prev();	//REF_AND_OFFSET_PUSH
					verify(ip, pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeFlag.REF, DataType.VAR);
					pInstr = prev();	//PUSHV var
					verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
					varName = getVar(pInstr.intVal, false);
					varIndex = decompile().toString();
					Var var = getVar(varName);
					String assignee = varName;
					if (var.isArray()) {
						assignee += "[" + varIndex + "]";
					}
					return new Expression(assignee + " = " + op2);
				} else if (pInstr.opcode == OPCode.REF_AND_OFFSET_PUSH && pInstr.flags == OPCodeFlag.REF) {
					//IDENTIFIER += EXPRESSION
					//IDENTIFIER\[EXPRESSION\] += EXPRESSION
					pInstr = prev();	//REF_AND_OFFSET_PUSH
					verify(ip, pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeFlag.REF, DataType.VAR);
					pInstr = prev();	//PUSHV var
					verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
					varName = getVar(pInstr.intVal, false);
					varIndex = decompile().toString();
					Var var = getVar(varName);
					String assignee = varName;
					if (var.isArray()) {
						assignee += "[" + varIndex + "]";
					}
					return new Expression(assignee + op2);
				} else {
					throw new DecompileException("Expected: POPI|REF_AND_OFFSET_PUSH", currentScript.getName(), ip, instr);
				}
			case JMP:
				if (currentBlock.is(BlockType.IF, BlockType.ELSIF, BlockType.ELSE) && ip == currentBlock.farEnd) {
					popBlock();
					return new Expression("end if");
				}
				return null;
			case JZ:
				if (currentBlock.exceptionHandlerBegin >= 0 && ip >= currentBlock.exceptionHandlerBegin) {
					//until CONDITION
					op1 = decompile();
					final int beginIndex = ip;
					//
					final int jmpExceptionHandlerEndIp = instr.intVal - 1;
					Instruction jmpExceptionHandlerEnd = instructions.get(jmpExceptionHandlerEndIp);
					verify(jmpExceptionHandlerEndIp, jmpExceptionHandlerEnd, OPCode.JMP, OPCodeFlag.FORWARD, DataType.INT);
					final int exceptionHandlerEndIp = jmpExceptionHandlerEnd.intVal;
					//
					final int brkexceptIp = instr.intVal - 2;
					Instruction brkexcept = instructions.get(brkexceptIp);
					verify(brkexceptIp, brkexcept, OPCode.BRKEXCEPT, 1, DataType.INT);
					//
					pushBlock(new Block(beginIndex, BlockType.UNTIL, jmpExceptionHandlerEndIp, exceptionHandlerEndIp));
					nextStatementIndex += 5;	//Skip implicit statements
					return new Expression("until " + op1);
				} else {
					if (instr.isForward()) {
						op1 = decompile();
						final int beginIndex = ip;
						if (currentBlock.is(BlockType.IF, BlockType.ELSIF) && beginIndex == currentBlock.end + 1) {
							if (op1.isBool() && op1.boolVal()) {
								//else
								final int endIfIp = currentBlock.farEnd;
								popBlock();
								//
								final int endThenIp = instr.intVal - 1;
								//
								pushBlock(new Block(beginIndex, BlockType.ELSE, endThenIp));
								currentBlock.farEnd = endIfIp;
								return new Expression("else");
							} else {
								//elsif CONDITION
								final int endIfIp = currentBlock.farEnd;
								popBlock();
								//
								final int endThenIp = instr.intVal - 1;
								//
								pushBlock(new Block(beginIndex, BlockType.ELSIF, endThenIp));
								currentBlock.farEnd = endIfIp;
								return new Expression("elsif " + op1);
							}
						} else {
							final int endThenIp = instr.intVal - 1;
							int jmpSkipCaseIp = endThenIp;
							Instruction jmpSkipCase = instructions.get(jmpSkipCaseIp);
							if (jmpSkipCase.flags == OPCodeFlag.FORWARD) {
								//if CONDITION
								while (jmpSkipCase.opcode == OPCode.JMP && jmpSkipCase.flags == OPCodeFlag.FORWARD && jmpSkipCase.intVal > jmpSkipCaseIp + 1) {
									jmpSkipCaseIp = jmpSkipCase.intVal;
									jmpSkipCase = instructions.get(jmpSkipCaseIp);
								}
								//
								pushBlock(new Block(beginIndex, BlockType.IF, endThenIp));
								currentBlock.farEnd = jmpSkipCaseIp;
								return new Expression("if " + op1);
							} else {
								//while CONDITION
								Instruction except = peek(-1);
								verify(ip - 1, except, OPCode.EXCEPT, 1, DataType.INT);
								pushBlock(new Block(beginIndex, BlockType.WHILE, endThenIp, except.intVal));
								return new Expression("while " + op1);
							}
						}
					} else {
						pInstr = peek(-1);
						if (pInstr.is(NativeFunction.START_DIALOGUE)) {
							nInstr = peek(+1);
							if (nInstr.is(NativeFunction.START_CAMERA_CONTROL)) {
								nInstr = peek(+3);
								if (nInstr.is(NativeFunction.START_GAME_SPEED)) {
									nInstr = peek(+4);
									nInstr2 = peek(+5);
									if (nInstr.opcode == OPCode.PUSH && nInstr2.is(NativeFunction.SET_WIDESCREEN)) {
										this.nextStatementIndex += 5;
										incTabs = true;
										return new Expression("begin cinema");
									} else {
										this.nextStatementIndex += 3;
										incTabs = true;
										return new Expression("begin camera");
									}
								}
							} else {
								incTabs = true;
								return new Expression("begin dialogue");
							}
						} else {
							//wait until CONDITION
							op1 = decompile();
							return new Expression("wait until " + op1);
						}
					}
				}
			case EXCEPT:
				final int exceptionHandlerBegin = instr.intVal;
				next();	//Skip EXCEPT
				nInstr = findEndOfStatement();
				if (nInstr.opcode != OPCode.JZ || !nInstr.isForward()) {
					//begin loop
					final int beginIndex = ip;
					gotoAddress(beginIndex);
					pushBlock(new Block(beginIndex, BlockType.LOOP, -1, exceptionHandlerBegin));
					return new Expression("begin loop");
				}
				return null;
			case BRKEXCEPT:
				if (currentBlock.type == BlockType.UNTIL) {
					popBlock();
					return null;
				}
				break;
			case ENDEXCEPT:
				return null;
			case ITEREXCEPT:
				Block block = currentBlock;
				popBlock();
				if (block.type == BlockType.SCRIPT) {
					return null;
				} else if (block.type == BlockType.LOOP) {
					return new Expression("end loop");
				} else if (block.type == BlockType.WHILE) {
					return new Expression("end while");
				}
				break;
			case RETEXCEPT:
				break;	//Never found
			case SWAP:
				if (instr.intVal == 0) {
					pInstr = peek(-1);	//PUSHC 0
					if (pInstr.opcode == OPCode.PUSH
							&& pInstr.flags == 1
							&& pInstr.dataType == DataType.COORDS
							&& pInstr.floatVal == 0f) {
						prev();				//PUSHC 0
						verify(ip, pInstr, OPCode.PUSH, 1, DataType.COORDS, 0f);
						pInstr = prev();	//CASTC
						verify(ip, pInstr, OPCode.CAST, 1, DataType.COORDS);
						op2 = decompile();
						pInstr = prev();	//CASTC
						verify(ip, pInstr, OPCode.CAST, 1, DataType.COORDS);
						op1 = decompile();
						return new Expression("[" + op1 + ", " + op2 + "]");
					}
				}
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
				break;	//Never found
			case ABS:
				op1 = decompile();
				return new Expression("abs " + op1.safe());
			case LINE:
				break;	//Never found
			case END:
				return END_SCRIPT;
		}
		throw new DecompileException("Unsupported instruction in "+currentBlock+" "+currentBlock.begin, currentScript.getName(), ip, instr);
	}
	
	private void pushBlock(Block block) {
		blocks.add(block);
		currentBlock = block;
		incTabs = true;
	}
	
	private void popBlock() {
		blocks.remove(blocks.size() - 1);
		currentBlock = blocks.isEmpty() ? null : blocks.get(blocks.size() - 1);
		decTabs();
	}
	
	private Expression decompile(NativeFunction func) throws DecompileException {
		Instruction pInstr, nInstr;
		//Special cases (without parameters)
		switch (func) {
			case SET_CAMERA_FOCUS:
				pInstr = peek(-1);		//SYS SET_CAMERA_POSITION ?
				if (pInstr.opcode == OPCode.SYS) {
					//set camera to IDENTIFIER CONSTANT
					verify(ip - 1, pInstr, NativeFunction.SET_CAMERA_POSITION);
					pInstr = peek(-2);	//SYS CONVERT_CAMERA_POSITION
					verify(ip - 2, pInstr, NativeFunction.CONVERT_CAMERA_POSITION);
					pInstr = peek(-3);	//PUSHI const
					verify(ip - 3, pInstr, OPCode.PUSH, 1, DataType.INT);
					int val = pInstr.intVal;
					pInstr = peek(-4);	//SYS CONVERT_CAMERA_FOCUS
					verify(ip - 4, pInstr, NativeFunction.CONVERT_CAMERA_FOCUS);
					pInstr = peek(-5);	//PUSHI const
					verify(ip - 5, pInstr, OPCode.PUSH, 1, DataType.INT, val);
					String op1 = getSymbol(ArgType.ScriptCameraPosition, val);
					return new Expression("set camera to "+op1+" "+op1);
				}
				break;
			case MOVE_CAMERA_FOCUS:
				pInstr = peek(-1);		//SYS MOVE_CAMERA_POSITION ?
				if (pInstr.opcode == OPCode.SYS) {
					//move camera to IDENTIFIER CONSTANT time EXPRESSION
					verify(ip - 1, pInstr, NativeFunction.MOVE_CAMERA_POSITION);
					pInstr = peek(-2);	//SWAPF 4
					verify(ip - 2, pInstr, OPCode.SWAP, 1, DataType.FLOAT, 4);
					pInstr = peek(-3);	//PUSHF time
					verify(ip - 3, pInstr, OPCode.PUSH, 1, DataType.FLOAT);
					String op2 = String.valueOf(pInstr.floatVal);
					pInstr = peek(-4);	//SYS CONVERT_CAMERA_POSITION
					verify(ip - 4, pInstr, NativeFunction.CONVERT_CAMERA_POSITION);
					pInstr = peek(-5);	//PUSHI const
					verify(ip - 5, pInstr, OPCode.PUSH, 1, DataType.INT);
					int val = pInstr.intVal;
					pInstr = peek(-6);	//SYS CONVERT_CAMERA_FOCUS
					verify(ip - 6, pInstr, NativeFunction.CONVERT_CAMERA_FOCUS);
					pInstr = peek(-7);	//PUSHI const
					verify(ip - 7, pInstr, OPCode.PUSH, 1, DataType.INT, val);
					String op1 = getSymbol(ArgType.ScriptCameraPosition, val);
					return new Expression("move camera to "+op1+" "+op1+" time "+op2);
				}
				break;
			case START_CANNON_CAMERA:
				incTabs = true;
				return new Expression("begin cannon");
			case SET_WIDESCREEN:
				accept(NativeFunction.SET_WIDESCREEN);
				accept(NativeFunction.END_GAME_SPEED);
				accept(NativeFunction.END_CAMERA_CONTROL);
				accept(NativeFunction.END_DIALOGUE);
				nInstr = peek(+1);
				if (nInstr.is(NativeFunction.GAME_HOLD_WIDESCREEN)) {
					accept(NativeFunction.GAME_HOLD_WIDESCREEN);
					this.nextStatementIndex = ip + 1;
					decTabs();
					return new Expression("end cinema with widescreen");
				} else {
					this.nextStatementIndex = ip + 1;
					decTabs();
					return new Expression("end cinema");
				}
			case END_GAME_SPEED:
				accept(NativeFunction.END_GAME_SPEED);
				accept(NativeFunction.END_CAMERA_CONTROL);
				accept(NativeFunction.END_DIALOGUE);
				this.nextStatementIndex = ip + 1;
				decTabs();
				return new Expression("end camera");
			case RELEASE_DUAL_CAMERA:
				decTabs();
				return new Expression("end dual camera");
			case END_CANNON_CAMERA:
				decTabs();
				return new Expression("end cannon");
			case END_DIALOGUE:
				decTabs();
				return new Expression("end dialogue");
			default:
		}
		//Read parameters
		int argc = 0;
		List<Expression> params = new LinkedList<>();
		for (int i = func.args.length - 1; i >= 0; i--) {
			if (i > 0 && func.args[i - 1].varargs) {
				Expression expr = decompile();
				params.add(0, expr);
				argc = expr.intVal();
			} else if (func.args[i].varargs) {
				if (argc > 0) {
					String argv = decompile().toString();
					for (int j = 1; j < argc; j++) {
						argv = decompile() + ", " + argv;
					}
					params.add(0, new Expression(argv));
				}
			} else {
				Expression expr = decompile();
				params.add(0, expr);
			}
		}
		//Special cases (with parameters)
		switch (func) {
			case CREATE:
				//Object CREATE(SCRIPT_OBJECT_TYPE type, SCRIPT_OBJECT_SUBTYPE subtype, Coord position)
				String typeStr = getSymbol(func.args[0].type, params.get(0).intVal(), false);
				if ("SCRIPT_OBJECT_TYPE_MARKER".equals(typeStr)) {
					//marker at COORD_EXPR
					if (params.get(1).intVal() != 0) {
						throw new DecompileException("Unexpected subtype: "+params.get(1)+". Expected 0", currentScript.getName(), ip, instructions.get(ip));
					}
					return new Expression("marker at " + params.get(2));
				}
				break;
			case SNAPSHOT:
				params.remove(3);	//implicit focus
				params.remove(2);	//implicit position
				break;
			case UPDATE_SNAPSHOT_PICTURE:
				params.remove(2);	//implicit focus
				params.remove(1);	//implicit position
				break;
			case START_DUAL_CAMERA:
				incTabs = true;
				return new Expression("begin dual camera to "+params.get(0)+" "+params.get(1));
			case CREATURE_AUTOSCALE:
				boolean enable = params.get(0).boolVal();
				if (enable) {
					//enable OBJECT auto scale EXPRESSION
					return new Expression("enable "+params.get(1)+" auto scale "+params.get(2));
				} else {
					//disable OBJECT auto scale
					return new Expression("disable "+params.get(1)+" auto scale");
				}
			default:
		}
		//Format the function call
		Symbol symbol = statements[func.ordinal()];
		if (symbol != null) {
			String statement = decompile(func, symbol, params.listIterator());
			return new Expression(statement);
		}
		throw new DecompileException(func+" is not supported", currentScript.getName(), ip, instructions.get(ip));
	}
	
	private String decompile(NativeFunction func, Symbol symbol, ListIterator<Expression> params) throws DecompileException {
		List<String> statement = new LinkedList<>();
		String subtype = null;
		for (Symbol sym : symbol.expression) {
			if (sym.terminal) {
				if (sym.terminalType == TerminalType.KEYWORD) {
					statement.add(sym.keyword);
				} else if (sym.terminalType != TerminalType.EOL) {
					String param = decompile(func, params);
					statement.add(param);
				}
			} else if (sym.alternatives != null) {
				String dummy = dummyOptions.get(sym.keyword);
				if (dummy != null) {
					statement.add(dummy);
				} else {
					String[] words = boolOptions.get(sym.keyword);
					if (words != null) {
						Expression param = params.next();
						boolean val = param.boolVal();
						String opt = val ? words[0] : words[1];
						statement.add(opt);
					} else {
						words = enumOptions.get(sym.keyword);
						if (words != null) {
							Expression param = params.next();
							int val = param.intVal();
							String opt = words[val];
							if (opt != null) {
								statement.add(opt);
							}
						} else {
							String param = decompile(func, params);
							statement.add(param);
						}
					}
				}
			} else if (sym.expression != null) {
				String[] words = enumOptions.get(sym.keyword);
				if (words != null) {
					Expression param = params.next();
					int val = param.intVal();
					String opt = words[val];
					if (opt != null) {
						statement.add(opt);
					}
				} else {
					Argument arg = func.args[params.nextIndex()];
					if (arg.type == ArgType.SCRIPT) {
						Expression param = params.next();
						int strptr = param.intVal();
						String string = chl.getDataSection().getString(strptr);
						statement.add(string);
					} else if (arg.type == ArgType.SCRIPT_OBJECT_TYPE) {
						Expression param = params.next();
						subtype = null;
						if (param.isNumber()) {
							int val = param.intVal();
							String typeName = getEnumEntry(ArgType.SCRIPT_OBJECT_TYPE, val);
							if (typeName != null) {
								subtype = subtypes.get(typeName);
							}
							String alias = getSymbol(ArgType.SCRIPT_OBJECT_TYPE, val);
							statement.add(alias);
						} else {
							statement.add(param.toString());
						}
					} else if (arg.type == ArgType.SCRIPT_OBJECT_SUBTYPE && subtype != null) {
						Expression param = params.next();
						if (param.isNumber()) {
							int val = param.intVal();
							String alias = getSymbol(subtype, val);
							statement.add(alias);
						} else {
							statement.add(param.toString());
						}
						subtype = null;
					} else {
						String param = decompile(func, sym, params);
						statement.add(param);
					}
				}
			} else {
				throw new DecompileException("Bad symbol: "+sym, currentScript.getName(), ip, instructions.get(ip));
			}
		}
		//
		ListIterator<String> tokens = statement.listIterator();
		StringBuilder res = new StringBuilder();
		String prevToken = tokens.next();
		res.append(prevToken);
		while (tokens.hasNext()) {
			String token = tokens.next();
			if (!in(prevToken, "[", "(") && !in(token, "]", ")")) {
				res.append(" ");
			}
			res.append(token);
			prevToken = token;
		}
		return res.toString();
	}
	
	private String decompile(NativeFunction func, ListIterator<Expression> params) {
		Argument arg = func.args[params.nextIndex()];
		Expression param = params.next();
		if (arg.type == ArgType.STRPTR && param.isNumber()) {
			int strptr = param.intVal();
			String string = chl.getDataSection().getString(strptr);
			return escape(string);
		} else if (arg.type.isEnum && param.isNumber()) {
			int val = param.intVal();
			String alias = getSymbol(arg.type, val);
			return alias;
		} else {
			return param.toString();
		}
	}
	
	private void trace(String string) {
		if (traceEnabled) {
			out.println(string);
		}
	}
	
	private void trace(Instruction instr) {
		if (traceEnabled && ip > lastTracedIp) {
			out.println(String.format("\t%-50s %s", instr, stack));
			lastTracedIp = ip;
		}
	}
	
	private Instruction next() throws DecompileException {
		if (!it.hasNext()) {
			throw new DecompileException("No more instructions", currentScript.getName(), it.previousIndex());
		}
		Instruction instr = it.next();
		ip = it.previousIndex();
		stackDo(instr);
		trace(instr);
		return instr;
	}
	
	private Instruction prev() throws DecompileException {
		Instruction instr = it.previous();
		ip = it.nextIndex();
		stackUndo(instr);
		return instr;
	}
	
	private void stackDo(Instruction instr) throws DecompileException {
		if (instr.opcode == OPCode.SYS) {
			try {
				NativeFunction func = NativeFunction.fromCode(instr.intVal);
				int argc = 0;
				for (int i = func.args.length - 1; i >= 0; i--) {
					if (i > 0 && func.args[i - 1].varargs) {
						argc = pop().intVal();
						argcStack.add(argc);
					} else if (func.args[i].varargs) {
						for (int j = 0; j < argc; j++) {
							pop();	//argv
						}
						argc = 0;
					} else {
						ArgType type = func.args[i].type;
						for (int j = 0; j < type.stackCount; j++) {
							pop();
						}
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
		} else if (instr.opcode == OPCode.SWAP && instr.dataType != DataType.INT) {
			StackVal val = stack.get(stack.size() - 1);
			stack.add(stack.size() - instr.intVal, val);
		} else if (instr.opcode == OPCode.PUSH) {
			ArgType type = typeMap[instr.dataType.ordinal()];
			switch (type) {
				case BOOL:
					push(instr.boolVal);
					break;
				case FLOAT:
					push(instr.floatVal);
					break;
				case COORD:
					push(new StackVal(type, instr.floatVal));
					break;
				default:
					push(new StackVal(type, instr.intVal));
			}
		} else if ((instr.opcode == OPCode.ADD || instr.opcode == OPCode.SUB) && instr.dataType == DataType.COORDS) {
			pop();
			pop();
			pop();
			pop();
			pop();
			pop();
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
		} else if ((instr.opcode == OPCode.MUL || instr.opcode == OPCode.DIV) && instr.dataType == DataType.COORDS) {
			pop();
			pop();
			pop();
			pop();
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
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
				int argc = 0;
				for (int i = 0; i < func.args.length; i++) {
					if (func.args[i].varargs) {
						argc = argcStack.remove(argcStack.size() - 1);
						for (int j = 0; j < argc; j++) {
							push(ArgType.UNKNOWN);
						}
					} else if (i > 0 && func.args[i - 1].varargs) {
						push(argc);
						argc = 0;
					} else {
						ArgType type = func.args[i].type;
						for (int j = 0; j < type.stackCount; j++) {
							push(type);
						}
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
		} else if (instr.opcode == OPCode.SWAP && instr.dataType != DataType.INT) {
			stack.remove(stack.size() - 1 - instr.intVal);
		} else if ((instr.opcode == OPCode.ADD || instr.opcode == OPCode.SUB) && instr.dataType == DataType.COORDS) {
			pop();
			pop();
			pop();
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
		} else if (instr.opcode == OPCode.MUL && instr.dataType == DataType.COORDS) {
			pop();
			pop();
			pop();
			push(ArgType.FLOAT);
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
		} else if (instr.opcode == OPCode.DIV && instr.dataType == DataType.COORDS) {
			pop();
			pop();
			pop();
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.COORD);
			push(ArgType.FLOAT);
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
		verify(it.previousIndex(), instr, opcode, flags, type, arg);
		return instr;
	}
	
	private Instruction accept(NativeFunction func) throws DecompileException {
		Instruction instr = next();
		verify(it.previousIndex(), instr, OPCode.SYS, 1, null, func.ordinal());
		return instr;
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type) throws DecompileException {
		if (instr.opcode != opcode
				|| instr.flags != flags
				|| (instr.dataType != type && type != null)) {
			throw new DecompileException("Expected "+opcode, currentScript.getName(), index, instr);
		}
	}
	
	private void verify(int index, Instruction instr, NativeFunction func) throws DecompileException {
		verify(index, instr, OPCode.SYS, 1, null, func.ordinal());
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type, int arg) throws DecompileException {
		if (instr.opcode != opcode
				|| instr.flags != flags
				|| (instr.dataType != type && type != null)
				|| instr.intVal != arg) {
			throw new DecompileException("Expected "+opcode, currentScript.getName(), index, instr);
		}
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type, float arg) throws DecompileException {
		if (instr.opcode != opcode
				|| instr.flags != flags
				|| (instr.dataType != type && type != null)
				|| instr.floatVal != arg) {
			throw new DecompileException("Expected "+opcode, currentScript.getName(), index, instr);
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
		stack.add(new StackVal(type));
	}
	
	private void push(int intVal) {
		stack.add(new StackVal(ArgType.INT, intVal));
	}
	
	private void push(float floatVal) {
		stack.add(new StackVal(ArgType.FLOAT, floatVal));
	}
	
	private void push(boolean boolVal) {
		stack.add(new StackVal(ArgType.BOOL, boolVal));
	}
	
	private void push(StackVal val) {
		stack.add(val);
	}
	
	private StackVal pop() throws DecompileException {
		if (stack.isEmpty()) {
			throw new DecompileException("Cannot pop value from empty stack", currentScript.getName(), ip, instructions.get(ip));
		}
		return stack.remove(stack.size() - 1);
	}
	
	private void decTabs() {
		tabs = tabs.substring(0, tabs.length() - 1);
		incTabs = false;
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
	
	
	private static String escape(String string) {
		string = string.replace("\\", "\\\\");
		string = string.replace("\"", "\\\"");
		return "\"" + string + "\"";
	}
	
	private static boolean in(String s, String...vals) {
		for (String val : vals) {
			if (s.equals(val)) return true;
		}
		return false;
	}
	
	private static String format(float v) {
		if (Math.abs(v) <= 16777216 && (int)v == v) {
			return String.valueOf((int)v);
		}
		return String.valueOf(v);
	}
	
	private static boolean isValidFilename(String s) {
		for (char c : ILLEGAL_CHARACTERS) {
			if (s.indexOf(c) >= 0) return false;
		}
		return true;
	}
	
	private static void loadStatements() {
		int lineno = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(Syntax.class.getResourceAsStream(STATEMENTS_FILE)));) {
			String line = "";
			while ((line = reader.readLine()) != null) {
				lineno++;
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] parts = line.split("\\s+", 2);
				if (parts.length != 2) {
					throw new RuntimeException("Wrong number of columns");
				}
				String funcName = parts[0];
				String statement = parts[1];
				NativeFunction func = NativeFunction.valueOf(funcName);
				Symbol symbol = Syntax.getSymbol(statement);
				if (symbol == null) {
					symbol = Syntax.getSymbol(statement + " EOL");	//handle standalone statements
				}
				if (symbol == null) {
					throw new RuntimeException("Symbol not found for statement \""+statement+"\"");
				}
				statements[func.ordinal()] = symbol;
			}
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage() + " at line " + lineno, e);
		}
	}
	
	private static void loadSubtypes() {
		int lineno = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(Syntax.class.getResourceAsStream(SUBTYPES_FILE)));) {
			String line = "";
			while ((line = reader.readLine()) != null) {
				lineno++;
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] parts = line.split("\\s+");
				if (parts.length != 2) {
					throw new RuntimeException("Wrong number of columns");
				}
				String typeName = parts[0];
				String subtypeName = parts[1];
				subtypes.put(typeName, subtypeName);
			}
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage() + " at line " + lineno, e);
		}
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
		public final DataType type;
		
		public Expression(String value) {
			this(value, false, null);
		}
		
		public Expression(String value, DataType type) {
			this(value, false, type);
		}
		
		public Expression(String value, boolean lowPriority) {
			this(value, lowPriority, null);
		}
		
		public Expression(String value, boolean lowPriority, DataType type) {
			this.value = value;
			this.lowPriority = lowPriority;
			this.type = type;
		}
		
		public boolean isNumber() {
			return type == DataType.FLOAT || type == DataType.INT;
		}
		
		public boolean isBool() {
			return type == DataType.BOOLEAN;
		}
		
		public int intVal() {
			if (!isNumber()) {
				throw new RuntimeException("Not a number");
			}
			return Integer.valueOf(value);
		}
		
		public float floatVal() {
			if (!isNumber()) {
				throw new RuntimeException("Not a number");
			}
			return Float.valueOf(value);
		}
		
		public boolean boolVal() {
			if (!isBool()) {
				throw new RuntimeException("Not a bool");
			}
			return "true".equals(value);
		}
		
		public String safe() {
			return lowPriority ? "("+value+")" : value;
		}
		
		@Override
		public String toString() {
			return value;
		}
	}
	
	
	private enum BlockType {
		SCRIPT, IF, ELSIF, ELSE, WHILE, LOOP, WHEN, UNTIL
	}
	
	
	private static class Block {
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
	
	
	private static class StackVal {
		public final ArgType type;
		private Integer intVal;
		private Float floatVal;
		private Boolean boolVal;
		
		public StackVal(ArgType type) {
			this.type = type;
		}
		
		public StackVal(ArgType type, int intVal) {
			this.type = type;
			this.intVal = intVal;
		}
		
		public StackVal(ArgType type, float floatVal) {
			this.type = type;
			this.floatVal = floatVal;
		}
		
		public StackVal(ArgType type, boolean boolVal) {
			this.type = type;
			this.boolVal = boolVal;
		}
		
		public int intVal() throws DecompileException {
			if (intVal == null) throw new DecompileException("Unknown stack value");
			return intVal;
		}
		
		public float floatVal() throws DecompileException {
			if (floatVal == null) throw new DecompileException("Unknown stack value");
			return floatVal;
		}
		
		public boolean boolVal() throws DecompileException {
			if (boolVal == null) throw new DecompileException("Unknown stack value");
			return boolVal;
		}
		
		@Override
		public String toString() {
			String r = type.toString();
			if (intVal != null) {
				r += " " + intVal;
			} else if (floatVal != null) {
				r += " " + floatVal;
			} else if (boolVal != null) {
				r += " " + boolVal;
			}
			return r;
		}
	}
}
