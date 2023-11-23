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
	
	private final Map<String, Map<Integer, String>> enums = new HashMap<>();
	private final Map<String, String> aliases = new HashMap<>();
	
	private final Map<String, Var> globalMap = new HashMap<>();
	private final Map<String, Var> localMap = new HashMap<>();
	private final ArrayList<ArgType> stack = new ArrayList<>();
	private List<Instruction> instructions;
	private ListIterator<Instruction> it;
	private int ip;
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
		String r = String.valueOf(val);
		Map<Integer, String> e = enums.get(type.name());
		if (e != null) {
			String enumEntry = e.get(val);
			if (enumEntry != null) {
				r = enumEntry;
				String alias = aliases.get(enumEntry);
				if (alias != null) {
					r = alias;
				}
			}
		}
		return r;
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
		Instruction popf = find(OPCode.POP, OPCodeFlag.REF, DataType.FLOAT, var.index);
		if (popf == null) {
			throw new DecompileException("Cannot find variable initialization statement");
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
					case COORDS:
						return op1;
					default:
				}
				break;
			case SYS:
				try {
					NativeFunction func = NativeFunction.fromCode(instr.intVal);
					if (func == NativeFunction.GET_PROPERTY) {
						pInstr = peek(-1);
						if (pInstr.opcode == OPCode.DUP) {
							//PROPERTY of IDENTIFIER += EXPRESSION
							next();
							return SELF_ASSIGN;
						} else {
							//PROPERTY of IDENTIFIER
							pInstr = prev();	//PUSHV [var]
							verify(ip, pInstr, OPCode.PUSH, OPCodeFlag.REF, DataType.VAR);
							varName = getVar(pInstr.intVal, false);
							pInstr = prev();	//PUSHI int
							verify(ip, pInstr, OPCode.PUSH, 1, DataType.INT);
							int propertyId = pInstr.intVal;
							String property = getSymbol(NativeFunction.GET_PROPERTY.args[0].type, propertyId);
							return new Expression(property + " of " + varName);
						}
					} else if (func == NativeFunction.SET_PROPERTY) {
						op2 = decompile();
						pInstr = peek(-1);
						if (pInstr.opcode == OPCode.POP && pInstr.flags == 1) {
							//PROPERTY of VARIABLE = EXPRESSION
							pInstr = prev();	//POPI
							verify(ip, pInstr, OPCode.POP, 1, DataType.INT);
							pInstr = prev();	//SYS2 GET_PROPERTY
							verify(ip, pInstr, OPCode.SYS, 1, null, NativeFunction.GET_PROPERTY.ordinal());
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
							verify(ip, pInstr, OPCode.SYS, 1, DataType.FLOAT, NativeFunction.GET_PROPERTY.ordinal());
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
							throw new DecompileException("Unexpected instruction "+instr+". Expected: POPI|SYS2", currentScript.getName(), ip);
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
							return new Expression(format(instr.floatVal));
						case INT:
							return new Expression(String.valueOf(instr.intVal));
						case BOOLEAN:
							return new Expression(instr.boolVal ? "true" : "false");
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
				verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
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
					verify(ip, pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeFlag.REF, DataType.FLOAT);
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
					verify(ip, pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeFlag.REF, DataType.FLOAT);
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
					throw new DecompileException("Unexpected instruction "+instr+". Expected: POPI|REF_AND_OFFSET_PUSH", currentScript.getName(), ip);
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
							if (op1.isTrue()) {
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
						//wait until CONDITION
						op1 = decompile();
						return new Expression("wait until " + op1);
					}
				}
			case EXCEPT:
				final int exceptionHandlerBegin = instr.intVal;
				next();	//Skip EXCEPT
				Instruction nInstr = findEndOfStatement();
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
		throw new DecompileException(instr+" is not supported in "+currentBlock, currentScript.getName(), ip);
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
		String statement = "";
		int nParams = func.pop;
		if (func.varargs) {
			nParams--;	//argc is implicit
			Instruction pushArgc = prev();
			verify(ip, pushArgc, OPCode.PUSH, 1, DataType.INT);
			int argc = pushArgc.intVal;
			if (argc > 0) {
				statement = decompile() + ")";
				for (int i = 1; i < argc; i++) {
					statement = decompile() + ", " + statement;
				}
				statement = "(" + statement;
			}
			Instruction pushScriptName = prev();
			verify(ip, pushScriptName, OPCode.PUSH, 1, DataType.INT);
			int strptr = pushScriptName.intVal;
			String scriptName = chl.getDataSection().getString(strptr);
			statement = " " + scriptName + statement;
		}
		//Read parameters
		String[] params = new String[nParams];
		for (int i = nParams - 1; i >= 0; i--) {
			params[i] = decompile().safe();
		}
		//Format the function call
		switch (func) {
			case ADD_REFERENCE:
				break;
			case ADD_RESOURCE:
				break;
			case ADD_SPOT_VISUAL_TARGET_OBJECT:
				break;
			case ADD_SPOT_VISUAL_TARGET_POS:
				break;
			case AFFECTED_BY_SNOW:
				break;
			case ALEX_SPECIAL_EFFECT_POSITION:
				break;
			case ATTACH_MUSIC:
				break;
			case ATTACH_OBJECT_LEASH_TO_HAND:
				break;
			case ATTACH_OBJECT_LEASH_TO_OBJECT:
				break;
			case ATTACH_SOUND_TAG:
				break;
			case ATTACH_TO_GAME:
				break;
			case BELIEF_FOR_PLAYER:
				break;
			case BUILD_BUILDING:
				break;
			case CALL:
				break;
			case CALL_BUILDING_IN_TOWN:
				break;
			case CALL_BUILDING_WOODPILE_IN_TOWN:
				break;
			case CALL_COMPUTER_PLAYER:
				return new Expression("get computer player " + params[0]);
			case CALL_FLYING:
				break;
			case CALL_IN:
				break;
			case CALL_IN_NEAR:
				break;
			case CALL_IN_NOT_NEAR:
				break;
			case CALL_NEAR:
				break;
			case CALL_NEAR_IN_STATE:
				break;
			case CALL_NOT_POISONED_IN:
				break;
			case CALL_PLAYER_CREATURE:
				break;
			case CALL_POISONED_IN:
				break;
			case CAMERA_PROPERTIES:
				break;
			case CAN_BE_LEASHED:
				break;
			case CAN_SKIP_CREATURE_TRAINING:
				break;
			case CAN_SKIP_TUTORIAL:
				break;
			case CHANGE_CLOUD_PROPERTIES:
				break;
			case CHANGE_INNER_OUTER_PROPERTIES:
				break;
			case CHANGE_LIGHTNING_PROPERTIES:
				break;
			case CHANGE_TIME_FADE_PROPERTIES:
				break;
			case CHANGE_WEATHER_PROPERTIES:
				break;
			case CLEAR_ACTOR_MIND:
				break;
			case CLEAR_CLICKED_OBJECT:
				break;
			case CLEAR_CLICKED_POSITION:
				break;
			case CLEAR_CLIPPING_WINDOW:
				break;
			case CLEAR_CONFINED_OBJECT:
				break;
			case CLEAR_DROPPED_BY_OBJECT:
				break;
			case CLEAR_HIT_LAND_OBJECT:
				break;
			case CLEAR_HIT_OBJECT:
				break;
			case CLEAR_PLAYER_SPELL_CHARGING:
				break;
			case CLEAR_SPELLS_ON_OBJECT:
				break;
			case CLING_SPIRIT:
				break;
			case COMPUTER_PLAYER_READY:
				break;
			case CONFINED_OBJECT:
				break;
			case CONVERT_CAMERA_FOCUS:
				break;
			case CONVERT_CAMERA_POSITION:
				break;
			case COUNTDOWN_TIMER_EXISTS:
				break;
			case CREATE:
				break;
			case CREATE_DUAL_CAMERA_WITH_POINT:
				break;
			case CREATE_HIGHLIGHT:
				break;
			case CREATE_MIST:
				break;
			case CREATE_PLAYER_TEMPLE:
				break;
			case CREATE_RANDOM_VILLAGER_OF_TRIBE:
				break;
			case CREATE_REACTION:
				break;
			case CREATE_REWARD:
				break;
			case CREATE_REWARD_IN_TOWN:
				break;
			case CREATE_TIMER:
				break;
			case CREATE_WITH_ANGLE_AND_SCALE:
				break;
			case CREATURE_AUTOSCALE:
				break;
			case CREATURE_CAN_LEARN:
				break;
			case CREATURE_CLEAR_FIGHT_QUEUE:
				break;
			case CREATURE_CREATE_RELATIVE_TO_CREATURE:
				break;
			case CREATURE_CREATE_YOUNG_WITH_KNOWLEDGE:
				break;
			case CREATURE_DESIRE_IS:
				break;
			case CREATURE_DO_ACTION:
				break;
			case CREATURE_FIGHT_QUEUE_HITS:
				break;
			case CREATURE_FORCE_FINISH:
				break;
			case CREATURE_FORCE_FRIENDS:
				break;
			case CREATURE_GET_NUM_TIMES_ACTION_PERFORMED:
				break;
			case CREATURE_HELP_ON:
				break;
			case CREATURE_INITIALISE_NUM_TIMES_PERFORMED_ACTION:
				break;
			case CREATURE_INTERACTING_WITH:
				break;
			case CREATURE_IN_DEV_SCRIPT:
				break;
			case CREATURE_LEARN_DISTINCTION_ABOUT_ACTIVITY_OBJECT:
				break;
			case CREATURE_LEARN_EVERYTHING:
				break;
			case CREATURE_LEARN_EVERYTHING_EXCLUDING:
				break;
			case CREATURE_REACTION:
				break;
			case CREATURE_SET_AGENDA_PRIORITY:
				break;
			case CREATURE_SET_DESIRE_ACTIVATED:
				break;
			case CREATURE_SET_DESIRE_ACTIVATED3:
				break;
			case CREATURE_SET_DESIRE_MAXIMUM:
				break;
			case CREATURE_SET_DESIRE_VALUE:
				break;
			case CREATURE_SET_KNOWS_ACTION:
				break;
			case CREATURE_SET_PLAYER:
				break;
			case CREATURE_SET_RIGHT_HAND_ONLY:
				break;
			case CREATURE_SPELL_REVERSION:
				break;
			case CREATURE_TURN_OFF_ALL_DESIRES:
				break;
			case CURRENT_PROFILE_HAS_CREATURE:
				break;
			case DANCE_CREATE:
				break;
			case DELETE_FRAGMENTS_FOR_OBJECT:
				break;
			case DELETE_FRAGMENTS_IN_RADIUS:
				break;
			case DETACH_FROM_GAME:
				break;
			case DETACH_MUSIC:
				break;
			case DETACH_OBJECT_LEASH:
				break;
			case DETACH_SOUND_TAG:
				break;
			case DETACH_UNDEFINED_FROM_GAME:
				break;
			case DEV_FUNCTION:
				break;
			case DLL_GETTIME:
				break;
			case DO_ACTION_AT_POS:
				break;
			case EFFECT_FROM_FILE:
				break;
			case ENABLE_DISABLE_ALIGNMENT_MUSIC:
				break;
			case ENABLE_DISABLE_COMPUTER_PLAYER1:
				break;
			case ENABLE_DISABLE_COMPUTER_PLAYER2:
				break;
			case ENABLE_DISABLE_MUSIC:
				break;
			case ENABLE_OBJECT_IMMUNE_TO_SPELLS:
				break;
			case END_CAMERA_CONTROL:
				break;
			case END_CANNON_CAMERA:
				break;
			case END_COUNTDOWN_TIMER:
				break;
			case END_DIALOGUE:
				break;
			case END_GAME_SPEED:
				break;
			case ENTER_EXIT_CITADEL:
				break;
			case FADE_ALL_DRAW_TEXT:
				break;
			case FADE_FINISHED:
				break;
			case FIRE_GUN:
				break;
			case FLOCK_ATTACH:
				break;
			case FLOCK_CREATE:
				break;
			case FLOCK_DETACH:
				break;
			case FLOCK_DISBAND:
				break;
			case FLOCK_MEMBER:
				break;
			case FLOCK_WITHIN_LIMITS:
				break;
			case FLY_SPIRIT:
				break;
			case FOCUS_AND_POSITION_FOLLOW:
				break;
			case FOCUS_FOLLOW:
				break;
			case FORCE_COMPUTER_PLAYER_ACTION:
				break;
			case GAME_ADD_FOR_BUILDING:
				break;
			case GAME_CLEAR_COMPUTER_PLAYER_ACTIONS:
				break;
			case GAME_CLEAR_DIALOGUE:
				break;
			case GAME_CLOSE_DIALOGUE:
				break;
			case GAME_CREATE_TOWN:
				break;
			case GAME_DELETE_FIRE:
				break;
			case GAME_DRAW_TEMP_TEXT:
				break;
			case GAME_DRAW_TEXT:
				break;
			case GAME_HOLD_WIDESCREEN:
				break;
			case GAME_PLAY_SAY_SOUND_EFFECT:
				break;
			case GAME_SET_MANA:
				break;
			case GAME_SOUND_PLAYING:
				break;
			case GAME_SUB_TYPE:
				break;
			case GAME_TEAM_SIZE:
				break;
			case GAME_THING_CAN_VIEW_CAMERA:
				break;
			case GAME_THING_CLICKED:
				break;
			case GAME_THING_FIELD_OF_VIEW:
				break;
			case GAME_THING_HIT:
				break;
			case GAME_THING_HIT_LAND:
				break;
			case GAME_TIME_ON_OFF:
				break;
			case GAME_TYPE:
				break;
			case GET_ACTION_COUNT:
				break;
			case GET_ACTION_TEXT_FOR_OBJECT:
				break;
			case GET_ALIGNMENT:
				break;
			case GET_ARENA:
				break;
			case GET_ARSE_POSITION:
				break;
			case GET_BELLY_POSITION:
				break;
			case GET_BRACELET_POSITION:
				break;
			case GET_CAMERA_FOCUS:
				break;
			case GET_CAMERA_POSITION:
				break;
			case GET_COMPUTER_PLAYER_ATTITUDE:
				break;
			case GET_COMPUTER_PLAYER_POSITION:
				break;
			case GET_COUNTDOWN_TIMER:
				break;
			case GET_COUNTDOWN_TIMER_TIME:
				break;
			case GET_CREATURE_CURRENT_ACTION:
				break;
			case GET_CREATURE_FIGHT_ACTION:
				break;
			case GET_CREATURE_KNOWS_ACTION:
				break;
			case GET_CREATURE_SPELL_SKILL:
				break;
			case GET_DEAD_LIVING:
				break;
			case GET_DESIRE:
				break;
			case GET_DISTANCE:
				break;
			case GET_EVENTS_PER_SECOND:
				break;
			case GET_FACING_CAMERA_POSITION:
				break;
			case GET_FIRST_HELP:
				break;
			case GET_FIRST_IN_CONTAINER:
				break;
			case GET_FOOTBALL_PITCH:
				break;
			case GET_GAME_TIME:
				break;
			case GET_HAND_POSITION:
				break;
			case GET_HAND_STATE:
				break;
			case GET_HELP:
				break;
			case GET_HIT_OBJECT:
				break;
			case GET_INCLUSION_DISTANCE:
				break;
			case GET_INFLUENCE:
				break;
			case GET_INTERACTION_MAGNITUDE:
				break;
			case GET_LANDING_POS:
				break;
			case GET_LAND_HEIGHT:
				break;
			case GET_LAST_HELP:
				break;
			case GET_LAST_OBJECT_WHICH_HIT_LAND:
				break;
			case GET_LAST_SPELL_CAST_POS:
				break;
			case GET_MANA:
				break;
			case GET_MANA_FOR_SPELL:
				break;
			case GET_MOON_PERCENTAGE:
				break;
			case GET_MOUSE_ACROSS:
				break;
			case GET_MOUSE_DOWN:
				break;
			case GET_MUSIC_ENUM_DISTANCE:
				break;
			case GET_MUSIC_OBJ_DISTANCE:
				break;
			case GET_NEAREST_TOWN_OF_PLAYER:
				break;
			case GET_NEXT_IN_CONTAINER:
				break;
			case GET_OBJECT_CLICKED:
				break;
			case GET_OBJECT_DESIRE:
				break;
			case GET_OBJECT_DESTINATION:
				break;
			case GET_OBJECT_DROPPED:
				break;
			case GET_OBJECT_EP:
				break;
			case GET_OBJECT_FADE:
				break;
			case GET_OBJECT_FLOCK:
				break;
			case GET_OBJECT_HAND_IS_OVER:
				break;
			case GET_OBJECT_HAND_POSITION:
				break;
			case GET_OBJECT_HELD:
				break;
			case GET_OBJECT_HELD1:
				break;
			case GET_OBJECT_LEASH_TYPE:
				break;
			case GET_OBJECT_OBJECT_LEASHED_TO:
				break;
			case GET_OBJECT_SCORE:
				break;
			case GET_OBJECT_STATE:
				break;
			case GET_OBJECT_WHICH_HIT:
				break;
			case GET_PLAYER_ALLY:
				break;
			case GET_PLAYER_TOWN_TOTAL:
				break;
			case GET_PLAYER_WIND_RESISTANCE:
				break;
			case GET_POSITION:
				break;
			case GET_PROPERTY:
				break;
			case GET_REAL_DAY1:
				break;
			case GET_REAL_DAY2:
				break;
			case GET_REAL_MONTH:
				break;
			case GET_REAL_TIME:
				break;
			case GET_REAL_YEAR:
				break;
			case GET_RESOURCE:
				break;
			case GET_SACRIFICE_TOTAL:
				break;
			case GET_SLOWEST_SPEED:
				break;
			case GET_SPELL_ICON_IN_TEMPLE:
				break;
			case GET_STORED_CAMERA_FOCUS:
				break;
			case GET_STORED_CAMERA_POSITION:
				break;
			case GET_TARGET_OBJECT:
				break;
			case GET_TARGET_RELATIVE_POS:
				break;
			case GET_TEMPLE_ENTRANCE_POSITION:
				break;
			case GET_TEMPLE_POSITION:
				break;
			case GET_TIMER_TIME_REMAINING:
				break;
			case GET_TIMER_TIME_SINCE_SET:
				break;
			case GET_TIME_SINCE:
				break;
			case GET_TIME_SINCE_OBJECT_ATTACKED:
				break;
			case GET_TOTAL_EVENTS:
				break;
			case GET_TOTEM_STATUE:
				break;
			case GET_TOWN_AND_VILLAGER_HEALTH_TOTAL:
				break;
			case GET_TOWN_WITH_ID:
				break;
			case GET_TOWN_WORSHIP_DEATHS:
				break;
			case GET_WALK_PATH_PERCENTAGE:
				break;
			case GUN_ANGLE_PITCH:
				break;
			case HAND_DEMO_TRIGGER:
				break;
			case HAS_CAMERA_ARRIVED:
				break;
			case HAS_MOUSE_WHEEL:
				break;
			case HAS_PLAYER_MAGIC:
				break;
			case HELP_SYSTEM_ON:
				break;
			case HIGHLIGHT_PROPERTIES:
				break;
			case ID_ADULT_SIZE:
				break;
			case ID_POISONED_SIZE:
				break;
			case ID_SIZE:
				break;
			case IMMERSION_EXISTS:
				break;
			case INFLUENCE_OBJECT:
				break;
			case INFLUENCE_POSITION:
				break;
			case INSIDE_TEMPLE:
				break;
			case IN_CREATURE_HAND:
				break;
			case IN_WIDESCREEN:
				break;
			case IS_ACTIVE:
				break;
			case IS_AFFECTED_BY_SPELL:
				break;
			case IS_AUTO_FIGHTING:
				break;
			case IS_CREATURE_AVAILABLE:
				break;
			case IS_DIALOGUE_READY:
				break;
			case IS_FIGHTING:
				break;
			case IS_FIRE_NEAR:
				break;
			case IS_KEEPING_OLD_CREATURE:
				break;
			case IS_LEASHED:
				break;
			case IS_LEASHED_TO_OBJECT:
				break;
			case IS_LOCKED_INTERACTION:
				break;
			case IS_OBJECT_IMMUNE_TO_SPELLS:
				break;
			case IS_OF_TYPE:
				break;
			case IS_ON_FIRE:
				break;
			case IS_PLAYING_HAND_DEMO:
				break;
			case IS_PLAYING_JC_SPECIAL:
				break;
			case IS_POISONED:
				break;
			case IS_SKELETON:
				break;
			case IS_SPELL_CHARGING:
				break;
			case IS_THAT_SPELL_CHARGING:
				break;
			case IS_WIND_MAGIC_AT_POS:
				break;
			case KEY_DOWN:
				break;
			case KILL_STORMS_IN_AREA:
				break;
			case LAST_MUSIC_LINE:
				break;
			case LOAD_COMPUTER_PLAYER_PERSONALITY:
				break;
			case LOAD_CREATURE:
				break;
			case LOAD_MAP:
				break;
			case LOAD_MY_CREATURE:
				break;
			case LOOK_AT_POSITION:
				break;
			case LOOK_GAME_THING:
				break;
			case MAP_SCRIPT_FUNCTION:
				break;
			case MOUSE_DOWN:
				break;
			case MOVE_CAMERA_FOCUS:
				break;
			case MOVE_CAMERA_LENS:
				break;
			case MOVE_CAMERA_POSITION:
				break;
			case MOVE_CAMERA_POS_FOC_LENS:
				break;
			case MOVE_CAMERA_TO_FACE_OBJECT:
				break;
			case MOVE_COMPUTER_PLAYER_POSITION:
				break;
			case MOVE_GAME_THING:
				break;
			case MOVE_GAME_TIME:
				break;
			case MOVE_MUSIC:
				break;
			case MUSIC_PLAYED1:
				break;
			case MUSIC_PLAYED2:
				break;
			case NONE:
				break;
			case NUM_MOUSE_BUTTONS:
				break;
			case OBJECT_ADULT_CAPACITY:
				break;
			case OBJECT_CAPACITY:
				break;
			case OBJECT_CAST_BY_OBJECT:
				break;
			case OBJECT_DELETE:
				break;
			case OBJECT_INFO_BITS:
				break;
			case OBJECT_RELATIVE_BELIEF:
				break;
			case OPPOSING_CREATURE:
				break;
			case OVERRIDE_STATE_ANIMATION:
				break;
			case PAUSE_UNPAUSE_CLIMATE_SYSTEM:
				break;
			case PAUSE_UNPAUSE_STORM_CREATION_IN_CLIMATE_SYSTEM:
				break;
			case PLAYED:
				break;
			case PLAYED_PERCENTAGE:
				break;
			case PLAYER_SPELL_CAST_TIME:
				break;
			case PLAYER_SPELL_LAST_CAST:
				break;
			case PLAY_GESTURE:
				break;
			case PLAY_HAND_DEMO:
				break;
			case PLAY_JC_SPECIAL:
				break;
			case PLAY_SOUND_EFFECT:
				break;
			case PLAY_SPIRIT_ANIM:
				break;
			case PLAY_SPIRIT_ANIM_IN_WORLD:
				break;
			case POPULATE_CONTAINER:
				break;
			case POSITION_CLICKED:
				break;
			case POSITION_FOLLOW:
				break;
			case POS_FIELD_OF_VIEW:
				break;
			case POS_VALID_FOR_CREATURE:
				break;
			case QUEUE_COMPUTER_PLAYER_ACTION:
				break;
			case RANDOM:
				break;
			case RANDOM_ULONG:
				break;
			case RELEASE_COMPUTER_PLAYER:
				break;
			case RELEASE_DUAL_CAMERA:
				break;
			case RELEASE_FROM_SCRIPT:
				break;
			case RELEASE_OBJECT_FOCUS:
				break;
			case REMOVE_REACTION:
				break;
			case REMOVE_REACTION_OF_TYPE:
				break;
			case REMOVE_REFERENCE:
				break;
			case REMOVE_RESOURCE:
				break;
			case RESET_GAME_TIME_PROPERTIES:
				break;
			case RESTART_MUSIC:
				break;
			case RESTART_OBJECT:
				break;
			case RESTORE_CAMERA_DETAILS:
				break;
			case RUN_CAMERA_PATH:
				break;
			case RUN_TEXT:
				break;
			case RUN_TEXT_WITH_NUMBER:
				break;
			case SAVE_COMPUTER_PLAYER_PERSONALITY:
				break;
			case SAVE_GAME_IN_SLOT:
				break;
			case SAY_SOUND_EFFECT_PLAYING:
				break;
			case SET_ACTIVE:
				break;
			case SET_AFFECTED_BY_WIND:
				break;
			case SET_ALIGNMENT:
				break;
			case SET_ANIMATION_MODIFY:
				break;
			case SET_ATTACK_OWN_TOWN:
				break;
			case SET_AVI_SEQUENCE:
				break;
			case SET_BOOKMARK_ON_OBJECT:
				break;
			case SET_BOOKMARK_POSITION:
				break;
			case SET_CAMERA_AUTO_TRACK:
				break;
			case SET_CAMERA_FOCUS:
				break;
			case SET_CAMERA_HEADING_FOLLOW:
				break;
			case SET_CAMERA_LENS:
				break;
			case SET_CAMERA_POSITION:
				break;
			case SET_CAMERA_POS_FOC_LENS:
				break;
			case SET_CAMERA_TO_FACE_OBJECT:
				break;
			case SET_CAMERA_ZONE:
				break;
			case SET_CANNON_PERCENTAGE:
				break;
			case SET_CANNON_STRENGTH:
				break;
			case SET_CAN_BUILD_WORSHIPSITE:
				break;
			case SET_CLIPPING_WINDOW:
				break;
			case SET_COMPUTER_PLAYER_ATTITUDE:
				break;
			case SET_COMPUTER_PLAYER_PERSONALITY:
				break;
			case SET_COMPUTER_PLAYER_POSITION:
				break;
			case SET_COMPUTER_PLAYER_SPEED:
				break;
			case SET_COMPUTER_PLAYER_SUPPRESSION:
				break;
			case SET_COUNTDOWN_TIMER_DRAW:
				break;
			case SET_CREATURE_AUTO_FIGHTING:
				break;
			case SET_CREATURE_CAN_DROP:
				break;
			case SET_CREATURE_CREED_PROPERTIES:
				break;
			case SET_CREATURE_DEV_STAGE:
				break;
			case SET_CREATURE_DISTANCE_FROM_HOME:
				break;
			case SET_CREATURE_FOLLOW_MASTER:
				break;
			case SET_CREATURE_HELP:
				break;
			case SET_CREATURE_HOME:
				break;
			case SET_CREATURE_IN_TEMPLE:
				break;
			case SET_CREATURE_MASTER:
				break;
			case SET_CREATURE_NAME:
				break;
			case SET_CREATURE_ONLY_DESIRE:
				break;
			case SET_CREATURE_ONLY_DESIRE_OFF:
				break;
			case SET_CREATURE_QUEUE_FIGHT_MOVE:
				break;
			case SET_CREATURE_QUEUE_FIGHT_SPELL:
				break;
			case SET_CREATURE_QUEUE_FIGHT_STEP:
				break;
			case SET_CREATURE_SOUND:
				break;
			case SET_DIE_ROLL_CHECK:
				break;
			case SET_DISCIPLE:
				break;
			case SET_DOLPHIN_MOVE:
				break;
			case SET_DOLPHIN_SPEED:
				break;
			case SET_DOLPHIN_WAIT:
				break;
			case SET_DRAW_HIGHLIGHT:
				break;
			case SET_DRAW_LEASH:
				break;
			case SET_DRAW_SCOREBOARD:
				break;
			case SET_DRAW_TEXT_COLOUR:
				break;
			case SET_FADE:
				break;
			case SET_FADE_IN:
				break;
			case SET_FIGHT_CAMERA_EXIT:
				break;
			case SET_FIGHT_LOCK:
				break;
			case SET_FIGHT_QUEUE_ONLY:
				break;
			case SET_FIXED_CAM_ROTATION:
				break;
			case SET_FOCUS:
				break;
			case SET_FOCUS_AND_POSITION_FOLLOW:
				break;
			case SET_FOCUS_FOLLOW:
				break;
			case SET_FOCUS_FOLLOW_COMPUTER_PLAYER:
				break;
			case SET_FOCUS_ON_OBJECT:
				break;
			case SET_GAMESPEED:
				break;
			case SET_GAME_SOUND:
				break;
			case SET_GAME_TIME:
				break;
			case SET_GAME_TIME_PROPERTIES:
				break;
			case SET_GRAPHICS_CLIPPING:
				break;
			case SET_HAND_DEMO_KEYS:
				break;
			case SET_HEADING_AND_SPEED:
				break;
			case SET_HELP_SYSTEM:
				break;
			case SET_HIGH_GRAPHICS_DETAIL:
				break;
			case SET_HURT_BY_FIRE:
				break;
			case SET_ID_MOVEABLE:
				break;
			case SET_ID_PICKUPABLE:
				break;
			case SET_INDESTRUCTABLE:
				break;
			case SET_INTERFACE_CITADEL:
				break;
			case SET_INTERFACE_INTERACTION:
				break;
			case SET_INTRO_BUILDING:
				break;
			case SET_LAND_BALANCE:
				break;
			case SET_LEASH_WORKS:
				break;
			case SET_MAGIC_IN_OBJECT:
				break;
			case SET_MAGIC_PROPERTIES:
				break;
			case SET_MAGIC_RADIUS:
				break;
			case SET_MIST_FADE:
				break;
			case SET_MUSIC_PLAY_POSITION:
				break;
			case SET_OBJECT_BELIEF_SCALE:
				break;
			case SET_OBJECT_CARRYING:
				break;
			case SET_OBJECT_COLOUR:
				break;
			case SET_OBJECT_FADE_IN:
				break;
			case SET_OBJECT_IN_PLAYER_HAND:
				break;
			case SET_OBJECT_LIGHTBULB:
				break;
			case SET_OBJECT_NAVIGATION:
				break;
			case SET_OBJECT_SCORE:
				break;
			case SET_OBJECT_TATTOO:
				break;
			case SET_ONLY_FOR_SCRIPTS:
				break;
			case SET_ON_FIRE:
				break;
			case SET_OPEN_CLOSE:
				break;
			case SET_PLAYER_ALLY:
				break;
			case SET_PLAYER_BELIEF:
				break;
			case SET_PLAYER_MAGIC:
				break;
			case SET_PLAYER_WIND_RESISTANCE:
				break;
			case SET_POISONED:
				break;
			case SET_POSITION:
				break;
			case SET_POSITION_FOLLOW:
				break;
			case SET_POSITION_FOLLOW_COMPUTER_PLAYER:
				break;
			case SET_PROPERTY:
				break;
			case SET_SCAFFOLD_PROPERTIES:
				break;
			case SET_SCRIPT_STATE:
				break;
			case SET_SCRIPT_STATE_WITH_PARAMS:
				break;
			case SET_SET_ON_FIRE:
				break;
			case SET_SKELETON:
				break;
			case SET_SUN_DRAW:
				break;
			case SET_TARGET:
				break;
			case SET_TEMPERATURE:
				break;
			case SET_TIMER_TIME:
				break;
			case SET_TOWN_DESIRE_BOOST:
				break;
			case SET_VILLAGER_SOUND:
				break;
			case SET_VIRTUAL_INFLUENCE:
				break;
			case SET_WIDESCREEN:
				break;
			case SEX_IS_MALE:
				break;
			case SHAKE_CAMERA:
				break;
			case SNAPSHOT:
				break;
			case SOUND_EXISTS:
				break;
			case SPECIAL_EFFECT_OBJECT:
				break;
			case SPECIAL_EFFECT_POSITION:
				break;
			case SPELL_AT_POINT:
				break;
			case SPELL_AT_POS:
				break;
			case SPELL_AT_THING:
				break;
			case SPIRIT_APPEAR:
				break;
			case SPIRIT_DISAPPEAR:
				break;
			case SPIRIT_EJECT:
				break;
			case SPIRIT_HOME:
				break;
			case SPIRIT_PLAYED:
				break;
			case SPIRIT_POINT_GAME_THING:
				break;
			case SPIRIT_POINT_POS:
				break;
			case SPIRIT_SCREEN_POINT:
				break;
			case SPIRIT_SPEAKS:
				break;
			case START_ANGLE_SOUND1:
				break;
			case START_ANGLE_SOUND2:
				break;
			case START_CAMERA_CONTROL:
				break;
			case START_CANNON_CAMERA:
				break;
			case START_COUNTDOWN_TIMER:
				break;
			case START_DIALOGUE:
				break;
			case START_DUAL_CAMERA:
				break;
			case START_GAME_SPEED:
				break;
			case START_IMMERSION:
				break;
			case START_MATCH_WITH_REFEREE:
				break;
			case START_MUSIC:
				break;
			case STOP_ALL_GAMES:
				break;
			case STOP_ALL_IMMERSION:
				break;
			case STOP_ALL_SCRIPTS_EXCLUDING:
				break;
			case STOP_ALL_SCRIPTS_IN_FILES_EXCLUDING:
				break;
			case STOP_DIALOGUE_SOUND:
				break;
			case STOP_IMMERSION:
				break;
			case STOP_LOOKING:
				break;
			case STOP_MUSIC:
				break;
			case STOP_POINTING:
				break;
			case STOP_SCRIPT:
				break;
			case STOP_SCRIPTS_IN_FILES:
				break;
			case STOP_SCRIPTS_IN_FILES_EXCLUDING:
				break;
			case STOP_SOUND_EFFECT:
				break;
			case STORE_CAMERA_DETAILS:
				break;
			case SWAP_CREATURE:
				break;
			case TEMP_TEXT:
				break;
			case TEMP_TEXT_WITH_NUMBER:
				break;
			case TEXT_READ:
				break;
			case THING_JC_SPECIAL:
				break;
			case THING_PLAY_ANIM:
				break;
			case THING_VALID:
				break;
			case TOGGLE_LEASH:
				break;
			case UPDATE_DUAL_CAMERA:
				break;
			case UPDATE_SNAPSHOT:
				break;
			case UPDATE_SNAPSHOT_PICTURE:
				break;
			case VORTEX_FADE_OUT:
				break;
			case VORTEX_PARAMETERS:
				break;
			case WALK_PATH:
				break;
			case WIDESCREEN_TRANSISTION_FINISHED:
				break;
			case WITHIN_ROTATION:
				break;
		}
		
		throw new DecompileException(func+" is not supported", currentScript.getName(), ip);
	}
	
	private Instruction next() throws DecompileException {
		if (!it.hasNext()) {
			throw new DecompileException("No more instructions", currentScript.getName(), it.previousIndex());
		}
		Instruction instr = it.next();
		ip = it.previousIndex();
		stackDo(instr);
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
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type) throws DecompileException {
		if (instr.opcode != opcode
				&& instr.flags != flags
				&& instr.dataType != type) {
			throw new DecompileException("Unexpected instruction: "+instr+". Expected "+opcode, currentScript.getName(), index);
		}
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type, int arg) throws DecompileException {
		if (instr.opcode != opcode
				&& instr.flags != flags
				&& (instr.dataType != type || type == null)
				&& instr.intVal != arg) {
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
			return "1".equals(value);
		}
		
		public boolean isTrue() {
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
		
		public Block(int begin) {
			this(begin, null, -1, -1);
		}
		
		public Block(int begin, BlockType type) {
			this(begin, type, -1, -1);
		}
		
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
}
