package it.ld.bw.chl.lang;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.ld.bw.chl.exceptions.DecompileException;
import it.ld.bw.chl.exceptions.InvalidNativeFunctionException;
import it.ld.bw.chl.exceptions.InvalidScriptIdException;
import it.ld.bw.chl.exceptions.InvalidVariableIdException;
import it.ld.bw.chl.exceptions.ParseException;
import it.ld.bw.chl.exceptions.ScriptNotFoundException;
import it.ld.bw.chl.lang.Symbol.TerminalType;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.DataType;
import it.ld.bw.chl.model.InitGlobal;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.NativeFunction;
import it.ld.bw.chl.model.NativeFunction.ArgType;
import it.ld.bw.chl.model.NativeFunction.Argument;
import it.ld.bw.chl.model.NativeFunction.Context;
import it.ld.bw.chl.model.OPCode;
import it.ld.bw.chl.model.OPCodeFlag;
import it.ld.bw.chl.model.Script;

public class CHLDecompiler {
	public static boolean traceEnabled = false;
	
	private static final String STATEMENTS_FILE = "statements.txt";
	
	private static final char[] ILLEGAL_CHARACTERS = {'/', '\n', '\r', '\t', '\0', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':'};
	
	private static final int SIGNIFICANT_DIGITS = 8;
	private static final DecimalFormat decimalFormat = new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
	
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
	private static final Map<String, String[]> boolOptions = new HashMap<>();
	private static final Map<String, String[]> enumOptions = new HashMap<>();
	private static final Map<String, String> dummyOptions = new HashMap<>();
	
	private static final int DEFAULT_SUBTYPE = 5000;
	private static final int AUDIO_SFX_BANK_TYPE_IN_GAME = 1;
	
	static {
		loadStatements();
		//
		addBoolOption("enable", "disable");
		addBoolOption("forward", "reverse");
		addBoolOption("open", "close");
		addBoolOption("pause", "unpause");
		addBoolOption("quest", "challenge");
		addBoolOption("enter", "exit");
		addBoolOption("left", "right");
		//
		addBoolOption("single line");
		addBoolOption("with pause on trigger");
		addBoolOption("without hand modify");
		addBoolOption("excluding scripted");
		addBoolOption("in world");
		addBoolOption("extra");
		addBoolOption("from sky");
		addBoolOption("as leader");
		addBoolOption("raw");
		addBoolOption("with fixed height");
		addBoolOption("destroys when placed");
		addBoolOption("with sound");
		addBoolOption("3d");
		//
		addEnumOption("HELP_SPIRIT_TYPE", "none", "good", "evil", "last");
		addEnumOption("SAY_MODE", null, "with interaction", "without interaction");
		addEnumOption("[anti]", null, "anti");
		addEnumOption("DELETE_MODE", null, "with fade", "with explosion", "with temple explosion");
		//
		dummyOptions.put("second|seconds", "seconds");
		dummyOptions.put("event|events", "events");
		dummyOptions.put("graphics|gfx", "graphics");
	}
	
	private static void addBoolOption(String wordTrue, String wordFalse) {
		boolOptions.put(wordTrue + "|" + wordFalse, new String[] {wordTrue, wordFalse});
	}
	
	private static void addBoolOption(String wordTrue) {
		boolOptions.put("[" + wordTrue + "]", new String[] {wordTrue, null});
	}
	
	private static void addEnumOption(String keyword, String...options) {
		enumOptions.put(keyword, options);
	}
	
	private final Map<String, String> subtypes = new HashMap<>();
	private final Map<String, Map<Integer, String>> enums = new HashMap<>();
	private final Map<String, String> aliases = new HashMap<>();
	
	private final Set<String> requiredScripts = new HashSet<>();
	private final Set<String> definedScripts = new HashSet<>();
	private final Map<String, String[]> scriptsParamTypes = new HashMap<>();
	private final Map<String, Var> globalMap = new HashMap<>();
	private final Map<String, Var> localMap = new HashMap<>();
	private final ArrayList<StackVal> stack = new ArrayList<>();
	private final ArrayList<Integer> argcStack = new ArrayList<>();
	private final ArrayList<String> typeContextStack = new ArrayList<>();
	private List<Instruction> instructions;
	private ListIterator<Instruction> it;
	private int ip;
	private int lastTracedIp;
	private int nextStatementIndex;
	private Script currentScript;
	private ArrayList<Block> blocks = new ArrayList<>();
	private Block currentBlock = null;
	
	private Writer writer;
	private int lineno;
	private String tabs = "";
	private boolean incTabs = false;
	
	private CHLFile chl;
	private Path path;
	
	private PrintStream out;
	
	private boolean respectLinenoEnabled = false;
	
	public CHLDecompiler() {
		this(System.out);
	}
	
	public CHLDecompiler(PrintStream out) {
		this.out = out;
	}
	
	public boolean isRespectLinenoEnabled() {
		return respectLinenoEnabled;
	}

	public void setRespectLinenoEnabled(boolean respectLinenoEnabled) {
		this.respectLinenoEnabled = respectLinenoEnabled;
	}
	
	public void loadSubtypes(File file) {
		int lineno = 0;
		try (BufferedReader reader = new BufferedReader(new FileReader(file));) {
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
	
	public void addHeader(File file) throws FileNotFoundException, IOException, ParseException {
		CHeaderParser parser = new CHeaderParser();
		Map<String, Map<String, Integer>> lEnums = new HashMap<>();
		parser.parse(file, null, lEnums);
		for (Entry<String, Map<String, Integer>> e : lEnums.entrySet()) {
			String enumName = e.getKey();
			if ("HelpTextEnums.h".equals(file.getName()) && enumName.startsWith("_unknown")) {
				enumName = "HELP_TEXT";
			}
			Map<String, Integer> enumEntries = e.getValue();
			Map<Integer, String> revEntries = enums.get(enumName);
			if (revEntries == null) {
				revEntries = new HashMap<>();
				enums.put(enumName, revEntries);
			}
			for (Entry<String, Integer> entry : enumEntries.entrySet()) {
				String entryName = entry.getKey();
				Integer entryVal = entry.getValue();
				String oldName = revEntries.put(entryVal, entryName);
				if (oldName != null) {
					out.println("NOTICE: entries "+oldName+" and "+entryName+" in "+enumName
								+" share the same value ("+entryVal+")");
				}
			}
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
		return getEnumEntry(type.name(), val);
	}
	
	private String getEnumEntry(String type, int val) {
		Map<Integer, String> e = enums.get(type);
		if (e != null) {
			return e.get(val);
		}
		return null;
	}
	
	public void decompile(CHLFile chl, File outdir) throws IOException, DecompileException {
		this.chl = chl;
		this.path = outdir.toPath();
		definedScripts.clear();
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
			writer = str;
			lineno = 1;
			writeln("//Source files list");
			if (!aliases.isEmpty()) {
				writeln("_aliases.txt");
			}
			for (String sourceFilename : sources) {
				if (!isValidFilename(sourceFilename)) {
					throw new RuntimeException("Invalid source filename: " + sourceFilename);
				}
				writeln(sourceFilename);
			}
			if (!chl.getAutoStartScripts().getScripts().isEmpty()) {
				writeln("_autorun.txt");
			}
		}
		//
		if (!aliases.isEmpty()) {
			out.println("Writing _aliases.txt");
			File autostartFile = path.resolve("_aliases.txt").toFile();
			try (FileWriter str = new FileWriter(autostartFile);) {
				writer = str;
				lineno = 1;
				writeHeader();
				writeAliases();
			}
		}
		//
		if (!chl.getAutoStartScripts().getScripts().isEmpty()) {
			out.println("Writing _autorun.txt");
			File autostartFile = path.resolve("_autorun.txt").toFile();
			try (FileWriter str = new FileWriter(autostartFile);) {
				writer = str;
				lineno = 1;
				writeHeader();
				writeAutoStartScripts();
			}
		}
		//
		for (String sourceFilename : sources) {
			if (!isValidFilename(sourceFilename)) {
				throw new RuntimeException("Invalid source filename: " + sourceFilename);
			}
			File sourceFile = path.resolve(sourceFilename).toFile();
			out.println("Writing "+sourceFilename);
			try (Writer str = new BufferedWriter(new FileWriter(sourceFile));) {
				writer = str;
				lineno = 1;
				writeHeader();
				writeScripts(sourceFilename);
			}
			if (!requiredScripts.isEmpty()) {
				trace("Inserting define script statements in "+sourceFilename);
				File tmpFile = path.resolve("_tmp.txt").toFile();
				sourceFile.renameTo(tmpFile);
				try (BufferedReader reader = new BufferedReader(new FileReader(tmpFile));
						Writer str = new BufferedWriter(new FileWriter(sourceFile));) {
					writer = str;
					List<String> header = new LinkedList<>();
					//Copy everything before first script
					String line = reader.readLine();
					while (!line.startsWith("begin ")) {
						header.add(line);
						line = reader.readLine();
					}
					//Try to make room for new statements
					for (int i = 0; i <= requiredScripts.size(); i++) {
						if (header.isEmpty()) break;
						if (!getLast(header).isBlank()) break;
						pop(header);
					}
					//Write header
					for (String h : header) {
						writer.write(h + "\r\n");
					}
					//Insert required scripts
					for (String name : requiredScripts) {
						try {
							Script script = chl.getScriptsSection().getScript(name);
							writer.write("define "+script.getSignature()+"\r\n");
						} catch (ScriptNotFoundException e) {
							throw new DecompileException(e.getMessage()+" at "+sourceFilename);
						}
					}
					writer.write("\r\n");
					//Copy code
					while (line != null) {
						writer.write(line + "\r\n");
						line = reader.readLine();
					}
				}
				tmpFile.delete();
				requiredScripts.clear();
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
	
	private void writeln(String string) throws IOException {
		writer.write(string + "\r\n");
		lineno++;
	}
	
	private void writeHeader() throws IOException {
		if (!respectLinenoEnabled) {
			writeln("//LHVM Challenge source version "+chl.getHeader().getVersion());
			writeln("//Decompiled with CHLASM tool by Daniels118");
			writeln("");
		}
	}
	
	private void writeAliases() throws IOException, DecompileException {
		for (Entry<String, String> entry : aliases.entrySet()) {
			String constant = entry.getKey();
			String alias = entry.getValue();
			writeln("global constant "+alias+" = "+constant);
		}
	}
	
	private void writeAutoStartScripts() throws IOException, DecompileException {
		for (int scriptID : chl.getAutoStartScripts().getScripts()) {
			try {
				Script script = chl.getScriptsSection().getScript(scriptID);
				writeln("run script "+script.getName());
			} catch (InvalidScriptIdException e) {
				String msg = "Invalid autorun script id: " + scriptID;
				writeln("//" + msg);
				throw new DecompileException(msg);
			}
		}
	}
	
	private void writeScripts(String sourceFilename) throws IOException, DecompileException {
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
		writeGlobals(firstGlobal, script.getGlobalCount());
		firstGlobal = script.getGlobalCount();
		writeln("");
		while (it.hasNext()) {
			script = it.next();
			if (!script.getSourceFilename().equals(sourceFilename)) {
				break;
			}
			decompile(script);
			writeln("");
			definedScripts.add(script.getName());
		}
	}
	
	private void writeGlobals(int start, int end) throws IOException {
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
				writeln(line);
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
	
	private void decompile(Script script) throws IOException, DecompileException {
		try {
			currentScript = script;
			scriptsParamTypes.put(script.getName(), new String[script.getParameterCount()]);
			//Load parameters on the stack
			for (int i = 0; i < script.getParameterCount(); i++) {
				push(ArgType.UNKNOWN);
			}
			//Begin script
			if (respectLinenoEnabled) {
				int i = script.getInstructionAddress() + 1;
				Instruction instr = instructions.get(i);
				while (instr.lineNumber <= 0) {
					instr = instructions.get(++i);
				}
				alignToLineno(instr.lineNumber);
			}
			trace("begin " + script.getSignature());
			writeln("begin " + script.getSignature());
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
					writeln("\t"+var.name+"["+var.size+"]");
				} else {															//atomic var
					Expression statement = decompileLocalVarAssignment(var);
					if (respectLinenoEnabled) alignToLineno();
					writeln("\t"+statement);
				}
			}
			//START
			accept(OPCode.ENDEXCEPT, OPCodeFlag.FREE, DataType.INT, 0);
			writeln("start");
			//
			tabs = "\t";
			incTabs = false;
			Context knownContext = getKnownContext(script);
			if (knownContext == Context.CINEMA) {
				writeln(tabs + "begin known cinema");
				tabs += "\t";
			} else if (knownContext == Context.DIALOGUE) {
				writeln(tabs + "begin known dialogue");
				tabs += "\t";
			}
			//
			Expression statement = decompileNextStatement();
			while (statement != END_SCRIPT) {
				if (statement != null) {
					if (respectLinenoEnabled) alignToLineno();
					writeln(tabs + statement);
				}
				if (incTabs) {
					tabs += "\t";	//Indentation must be delayed by one statement
					incTabs = false;
				}
				statement = decompileNextStatement();
			}
			//
			if (knownContext == Context.CINEMA) {
				writeln(tabs + "end known cinema");
			} else if (knownContext == Context.DIALOGUE) {
				writeln(tabs + "end known dialogue");
			}
			writeln("end script " + script.getName());
			trace("end script " + script.getName());
			if (!stack.isEmpty()) {
				out.println("Stack is not empty at end of script "+script.getName()+":");
				out.println(stack);
				out.println();
			}
			if (!blocks.isEmpty()) {
				out.println("Blocks stack is not empty at end of script "+script.getName()+":");
				out.println();
			}
		} catch (RuntimeException e) {
			throw new DecompileException(script, ip, instructions.get(ip), e);
		}
	}
	
	private Context getKnownContext(Script script) throws DecompileException {
		boolean requiresCamera = false;
		boolean requiresDialogue = false;
		boolean usesDialogue = false;
		final int lastInstr = Math.min(script.getLastInstructionAddress(), instructions.size() - 1);
		for (int i = script.getInstructionAddress(); i <= lastInstr; i++) {
			Instruction instr = instructions.get(i);
			if (instr.opcode == OPCode.SYS) {
				try {
					NativeFunction func = NativeFunction.fromCode(instr.intVal);
					switch (func) {
						case START_CAMERA_CONTROL:
							return null;
						case START_DIALOGUE:
							usesDialogue = true;
							break;
						default:
							if (func.context == Context.CAMERA) {
								requiresCamera = true;
								if (requiresDialogue) break;
							} else if (func.context == Context.DIALOGUE) {
								requiresDialogue = true;
								if (requiresCamera) break;
							}
					}
				} catch (InvalidNativeFunctionException e) {
					throw new DecompileException(script, i, instr, e);
				}
			} else if (instr.opcode == OPCode.END) {
				break;
			}
		}
		if (requiresCamera) {
			return Context.CINEMA;
		} else if (requiresDialogue && !usesDialogue) {
			return Context.DIALOGUE;
		}
		return null;
	}
	
	private void alignToLineno() throws IOException {
		Instruction instr = instructions.get(ip);
		if (instr.opcode != OPCode.JZ && instr.opcode != OPCode.JMP
				&& instr.opcode != OPCode.EXCEPT && instr.opcode != OPCode.ITEREXCEPT
				&& instr.opcode != OPCode.BRKEXCEPT
				&& instr.opcode != OPCode.END) {
			alignToLineno(instr.lineNumber);
		}
	}
	
	private void alignToLineno(int target) throws IOException {
		while (lineno < target) {
			writeln("");
		}
	}
	
	private Expression decompileLocalVarAssignment(Var var) throws DecompileException {
		int start = ip;
		Instruction popf = find(OPCode.POP, OPCodeFlag.REF, DataType.FLOAT, var.index);
		if (popf == null) {
			throw new DecompileException("Cannot find initialization statement for variable "+var, currentScript, start);
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
					if (op2.isNumber() && op2.floatVal() == 1) {
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
					if (op2.isNumber() && op2.floatVal() == 1) {
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
						if (op1.isVar()) {
							return new Expression("constant " + op1.safe(), op1.getVar());
						} else {
							return new Expression("constant " + op1.safe());
						}
					case FLOAT:
						op1 = decompile();
						if (op1.isNumber() && !op1.isExpression) {
							//Workaround for missing constants
							return new Expression(op1.safe());
						} else {
							return new Expression("variable " + op1.safe());
						}
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
					case BOOLEAN:
						//CASTB
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
						} else if (pInstr.opcode == OPCode.SWAP) {
							//OBJECT is CONST_EXPR
							prev();				//SWAP
							op2 = decompile();	//SCRIPT_OBJECT_PROPERTY_TYPE
							String property = op2.toString();
							if (op2.isNumber()) {
								property = getSymbol(ArgType.SCRIPT_OBJECT_PROPERTY_TYPE, op2.intVal());
							}
							op1 = decompile();	//OBJECT
							return new Expression(op1 + " is " + property);
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
							throw new DecompileException("Expected: POPI|SYS2", currentScript, ip, instr);
						}
					} else {
						return decompile(func);
					}
				} catch (InvalidNativeFunctionException e) {
					throw new DecompileException(currentScript, ip, e);
				}
			case CALL:
				//run [background] script IDENTIFIER[(parameters)]
				try {
					Script script = chl.getScriptsSection().getScript(instr.intVal);
					//If the script hasnt been defined yet, add it to the required "define script..."
					if (!definedScripts.contains(script.getName())) {
						requiredScripts.add(script.getName());
					}
					//Retrieve script parameter types
					String[] paramTypes = scriptsParamTypes.get(script.getName());
					if (paramTypes == null) {
						paramTypes = new String[script.getParameterCount()];
						scriptsParamTypes.put(script.getName(), paramTypes);
					}
					//
					String line = "run";
					if (instr.isStart()) {
						line += " background";
					}
					line += " script " + script.getName();
					if (script.getParameterCount() > 0) {
						typeContextStack.add(paramTypes[paramTypes.length - 1]);
						String params = decompile().toString();
						pop(typeContextStack);
						for (int i = script.getParameterCount() - 2; i >= 0; i--) {
							typeContextStack.add(paramTypes[i]);
							params = decompile() + ", " + params;
							pop(typeContextStack);
						}
						line += "(" + params + ")";
					}
					return new Expression(line);
				} catch (InvalidScriptIdException e) {
					throw new DecompileException(currentScript, ip, e);
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
					String var = getVarName(instr.intVal);
					return new Expression(var + " = " + decompile());
				} else {
					//statement
					return decompile();
				}
			case PUSH:
				if (instr.isReference()) {
					varName = getVarName(instr.intVal);
					if (varName.indexOf('[') < 0) {
						//IDENTIFIER\[NUMBER\]
						Var var = getVar(varName);
						return new Expression(var);
					} else {
						//IDENTIFIER
						return new Expression(varName);
					}
				} else {
					//NUMBER
					switch (instr.dataType) {
						case FLOAT:
							return new Expression(instr.floatVal);
						case INT:
							if (!typeContextStack.isEmpty()) {
								String type = getLast(typeContextStack);
								if (type != null) {
									String entry = getEnumEntry(type, instr.intVal);
									if (entry != null) {
										String alias = aliases.getOrDefault(entry, entry);
										return new Expression(alias, instr.intVal);
									}
								}
							}
							return new Expression(instr.intVal);
						case COORDS:
							op3 = new Expression(format(instr.floatVal));
							pInstr = prev();
							verify(ip, instr, OPCode.PUSH, 1, DataType.COORDS);
							op2 = new Expression(format(pInstr.floatVal));
							pInstr = prev();
							verify(ip, instr, OPCode.PUSH, 1, DataType.COORDS);
							op1 = new Expression(format(pInstr.floatVal));
							return new Expression("["+op1+", "+op2+", "+op3+"]", instr.dataType);
						case OBJECT:
							return new Expression("0", DataType.OBJECT);
						case BOOLEAN:
							return new Expression(instr.boolVal);
						case VAR:
							return new Expression(getVarName(instr.intVal, false));
						default:
					}
				}
				break;
			case REF_PUSH:
				//IDENTIFIER\[EXPRESSION\]
				verify(ip, instr, OPCode.REF_PUSH, OPCodeFlag.REF, DataType.VAR);
				pInstr = prev();	//ADDF
				verify(ip, pInstr, OPCode.ADD, 1, DataType.FLOAT);
				pInstr = prev();	//PUSHF var
				verify(ip, pInstr, OPCode.PUSH, 1, DataType.FLOAT);
				varName = getVarName((int)pInstr.floatVal, false);
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
					varName = getVarName(pInstr.intVal, false);
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
					varName = getVarName(pInstr.intVal, false);
					varIndex = decompile().toString();
					Var var = getVar(varName);
					String assignee = varName;
					if (var.isArray()) {
						assignee += "[" + varIndex + "]";
					}
					return new Expression(assignee + op2);
				} else {
					throw new DecompileException("Expected: POPI|REF_AND_OFFSET_PUSH", currentScript, ip, instr);
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
				final int beginIndex = ip + 1;
				Instruction jmp = instructions.get(exceptionHandlerBegin - 1);
				if (jmp.opcode == OPCode.JMP && jmp.intVal == beginIndex) {
					//begin loop
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
				Block prevBlock = currentBlock;
				popBlock();
				if (prevBlock.type == BlockType.SCRIPT) {
					return null;
				} else if (prevBlock.type == BlockType.LOOP) {
					return new Expression("end loop");
				} else if (prevBlock.type == BlockType.WHILE) {
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
		throw new DecompileException("Unsupported instruction in "+currentBlock+" "+currentBlock.begin, currentScript, ip, instr);
	}
	
	private void pushBlock(Block block) {
		blocks.add(block);
		currentBlock = block;
		incTabs = true;
	}
	
	private void popBlock() {
		pop(blocks);
		currentBlock = blocks.isEmpty() ? null : getLast(blocks);
		decTabs();
	}
	
	private Expression decompile(NativeFunction func) throws DecompileException {
		Instruction pInstr, nInstr;
		boolean anti;
		int strptr;
		String scriptName;
		//Special cases (without parameters)
		switch (func) {
			case SET_CAMERA_FOCUS:
				pInstr = peek(-1);		//SYS SET_CAMERA_POSITION ?
				if (pInstr.opcode == OPCode.SYS && pInstr.intVal == NativeFunction.SET_CAMERA_POSITION.ordinal()) {
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
				if (pInstr.opcode == OPCode.SYS && pInstr.intVal == NativeFunction.MOVE_CAMERA_POSITION.ordinal()) {
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
				nInstr = peek(0);
				if (nInstr.is(NativeFunction.END_DIALOGUE)) {
					accept(NativeFunction.END_DIALOGUE);
					nInstr = peek(0);
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
				} else {
					this.nextStatementIndex = ip + 1;
					decTabs();
					incTabs = true;
					return new Expression("end cinema with dialogue");
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
		List<Expression> params = readParameters(func);
		//Special cases (with parameters)
		switch (func) {
			case CREATE:
				//Object CREATE(SCRIPT_OBJECT_TYPE type, SCRIPT_OBJECT_SUBTYPE subtype, Coord position)
				if (params.get(0).isNumber()) {
					String typeStr = getSymbol(func.args[0].type, params.get(0).intVal(), false);
					if ("SCRIPT_OBJECT_TYPE_MARKER".equals(typeStr)) {
						//marker at COORD_EXPR
						if (params.get(1).intVal() != 0) {
							throw new DecompileException("Unexpected subtype: "+params.get(1)+". Expected 0", currentScript, ip, instructions.get(ip));
						}
						return new Expression("marker at " + params.get(2));
					}
				}
				break;
			case INFLUENCE_OBJECT:
				//SYS INFLUENCE_OBJECT(Object target, float radius, int zero, int anti)
				//create [anti] influence on OBJECT [radius EXPRESSION]
				anti = params.get(3).intVal() != 0;
				return new Expression("create"+(anti?" anti":"")+" influence on "+params.get(0)+" radius "+params.get(1));
			case INFLUENCE_POSITION:
				//INFLUENCE_POSITION(Coord position, float radius, int zero, int anti)
				anti = params.get(3).intVal() != 0;
				if (anti) {
					//create anti influence at position COORD_EXPR [radius EXPRESSION]
					return new Expression("create anti influence at position "+params.get(0)+" radius "+params.get(1));
				} else {
					//create influence at position COORD_EXPR [radius EXPRESSION]
					return new Expression("create influence at "+params.get(0)+" radius "+params.get(1));
				}
			case SNAPSHOT:
				params.set(2, null);	//implicit focus
				params.set(3, null);	//implicit position
				strptr = params.get(7).intVal();
				scriptName = chl.getDataSection().getString(strptr);
				if (!definedScripts.contains(scriptName)) {
					requiredScripts.add(scriptName);
				}
				break;
			case UPDATE_SNAPSHOT_PICTURE:
				params.set(1, null);	//implicit focus
				params.set(2, null);	//implicit position
				break;
			case UPDATE_SNAPSHOT:
				strptr = params.get(4).intVal();
				scriptName = chl.getDataSection().getString(strptr);
				if (!definedScripts.contains(scriptName)) {
					requiredScripts.add(scriptName);
				}
				break;
			case SAY_SOUND_EFFECT_PLAYING:
				params.set(0, null);	//always false
				break;
			case STOP_SOUND_EFFECT:
				params.set(0, null);	//always false
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
			Expression statement = decompile(func, symbol, params.listIterator());
			return statement;
		}
		throw new DecompileException(func+" is not supported", currentScript, ip, instructions.get(ip));
	}
	
	private List<Expression> readParameters(NativeFunction func) throws DecompileException {
		String subtype = null;
		int argc = 0;
		String[] scriptParamTypes = scriptsParamTypes.get(currentScript.getName());
		boolean guessed = false;
		List<Expression> params = new LinkedList<>();
		for (int i = func.args.length - 1; i >= 0; i--) {
			Argument arg = func.args[i];
			if (i > 0 && func.args[i - 1].varargs) {
				Expression expr = decompile();
				params.add(0, expr);
				argc = expr.intVal();
			} else if (arg.varargs) {
				if (argc > 0) {
					String argv = decompile().toString();
					for (int j = 1; j < argc; j++) {
						argv = decompile() + ", " + argv;
					}
					params.add(0, new Expression(argv));
				} else {
					params.add(0, new Expression(""));
				}
			} else {
				if (arg.type.isEnum) {
					typeContextStack.add(subtype != null ? subtype : arg.type.name());
					subtype = null;
				}
				Expression expr = decompile();
				params.add(0, expr);
				if (arg.type.isEnum) {
					if (arg.type == ArgType.AUDIO_SFX_BANK_TYPE && expr.isNumber()) {
						int val = expr.intVal();
						String entry = getEnumEntry(ArgType.AUDIO_SFX_BANK_TYPE, val);
						subtype = subtypes.get(entry);
					}
					pop(typeContextStack);
					//Try to guess the parameter type for the user script
					if (expr.isVar()) {
						Var var = expr.getVar();
						int localIndex = var.index - currentScript.getGlobalCount() - 1;
						if (localIndex >= 0 && localIndex < currentScript.getParameterCount()) {
							if (scriptParamTypes[localIndex] == null) {
								scriptParamTypes[localIndex] = arg.type.name();
								guessed = true;
							} else if (!arg.type.name().equals(scriptParamTypes[localIndex])
										&& !"?".equals(scriptParamTypes[localIndex])) {
								out.println("WARNING: parameter "+localIndex+" in "+currentScript.getName()+" is ambiguous");
								scriptParamTypes[localIndex] = "?";
							}
						}
					}
				}
			}
		}
		if (guessed) {
			out.println("INFO: guessed parameter types: "
						+currentScript.getName()+"("+String.join(", ", scriptParamTypes)+")");
		}
		return params;
	}
	
	private Expression decompile(NativeFunction func, Symbol symbol, ListIterator<Expression> params) throws DecompileException {
		if (symbol.terminal && symbol.terminalType == TerminalType.KEYWORD) {
			return new Expression(symbol.keyword);
		}
		List<Expression> statement = new LinkedList<>();
		String typeName = null;
		String subtype = null;
		int audioSfxIdIndex = -1;
		for (Symbol sym : symbol.expression) {
			skipNulls(params);	//Skip implicit parameters
			if (sym.terminal) {
				if (sym.terminalType == TerminalType.KEYWORD) {
					statement.add(new Expression(sym.keyword));
				} else if (sym.terminalType != TerminalType.EOL) {
					Expression param = decompile(func, params);
					statement.add(param);
				}
			} else if (sym.alternatives != null) {
				String dummy = dummyOptions.get(sym.keyword);
				if (dummy != null) {
					statement.add(new Expression(dummy));
				} else {
					String[] words = boolOptions.get(sym.keyword);
					if (words != null) {
						Expression param = params.next();
						boolean val = param.boolVal();
						String opt = val ? words[0] : words[1];
						if (opt != null) {
							statement.add(new Expression(opt));
						}
					} else {
						words = enumOptions.get(sym.keyword);
						if (words != null) {
							Expression param = params.next();
							int val = param.intVal();
							String opt = words[val];
							if (opt != null) {
								statement.add(new Expression(opt));
							}
						} else {
							Expression param = decompile(func, params);
							statement.add(param);
						}
					}
				}
			} else if (sym.expression != null) {
				String[] words = boolOptions.get(sym.keyword);
				if (words != null) {
					Expression param = params.next();
					boolean val = param.boolVal();
					String opt = val ? words[0] : words[1];
					if (opt != null) {
						statement.add(new Expression(opt));
					}
				} else {
					words = enumOptions.get(sym.keyword);
					if (words != null) {
						Expression param = params.next();
						int val = param.intVal();
						String opt = words[val];
						if (opt != null) {
							statement.add(new Expression(opt));
						}
					} else {
						Argument arg = func.args[params.nextIndex()];
						if (arg.type == ArgType.SCRIPT) {
							Expression param = params.next();
							int strptr = param.intVal();
							String string = chl.getDataSection().getString(strptr);
							statement.add(new Expression(string));
						} else if (arg.type == ArgType.SCRIPT_OBJECT_TYPE) {
							Expression param = params.next();
							subtype = null;
							if (param.isNumber()) {
								int val = param.intVal();
								String entry = getEnumEntry(ArgType.SCRIPT_OBJECT_TYPE, val);
								if (entry != null) {
									subtype = subtypes.get(entry);
								}
								String alias = getSymbol(ArgType.SCRIPT_OBJECT_TYPE, val);
								statement.add(new Expression(alias));
							} else {
								statement.add(param);
							}
						} else if (arg.type == ArgType.SCRIPT_OBJECT_SUBTYPE) {
							Expression param = params.next();
							if (param.isNumber()) {
								int val = param.intVal();
								if (val == DEFAULT_SUBTYPE) {
									statement.add(null);
								} else {
									if (subtype == null) {
										statement.add(param);
										//
										Instruction instr = instructions.get(ip);
										out.println("WARNING: subtype not defined for type "+typeName
												+" at instruction "+ip+" ("+instr+")"
												+" in script "+currentScript.getName()
												+" ("+currentScript.getSourceFilename()+":"+instr.lineNumber+")");
									} else {
										String alias = getSymbol(subtype, val);
										statement.add(new Expression(alias));
									}
								}
							} else {
								statement.add(param);
							}
							subtype = null;
						} else if (arg.type == ArgType.CREATURE_ACTION_LEARNING_TYPE) {
							Expression param = params.next();
							subtype = null;
							if (param.isNumber()) {
								int val = param.intVal();
								String entry = getEnumEntry(ArgType.CREATURE_ACTION_LEARNING_TYPE, val);
								if (entry != null) {
									subtype = subtypes.get(entry);
								}
								String alias = getSymbol(ArgType.CREATURE_ACTION_LEARNING_TYPE, val);
								statement.add(new Expression(alias));
							} else {
								statement.add(param);
							}
						} else if (arg.type == ArgType.CREATURE_ACTION_SUBTYPE) {
							Expression param = params.next();
							if (param.isNumber()) {
								int val = param.intVal();
								if (val == DEFAULT_SUBTYPE) {
									statement.add(null);
								} else {
									if (subtype == null) {
										statement.add(param);
										//
										Instruction instr = instructions.get(ip);
										out.println("WARNING: subtype not defined for type "+typeName
												+" at instruction "+ip+" ("+instr+")"
												+" in script "+currentScript.getName()
												+" ("+currentScript.getSourceFilename()+":"+instr.lineNumber+")");
									} else {
										String alias = getSymbol(subtype, val);
										statement.add(new Expression(alias));
									}
								}
							} else {
								statement.add(param);
							}
							subtype = null;
						} else if (arg.type == ArgType.AUDIO_SFX_BANK_TYPE) {
							Expression param = params.next();
							if (param.isNumber()) {
								int val = param.intVal();
								Expression audioSfxId = statement.get(audioSfxIdIndex);
								if (audioSfxId.isNumber()) {
									String entry = getEnumEntry(ArgType.AUDIO_SFX_BANK_TYPE, val);
									subtype = subtypes.get(entry);
									if (subtype != null) {
										String alias = getSymbol(subtype, audioSfxId.intVal());
										statement.set(audioSfxIdIndex, new Expression(alias));
									} else {
										statement.set(audioSfxIdIndex, new Expression("constant "+audioSfxId));
									}
								}
								if (val == AUDIO_SFX_BANK_TYPE_IN_GAME) {
									statement.add(null);
								} else {
									String alias = getSymbol(arg.type, val);
									statement.add(new Expression(alias));
								}
							} else {
								statement.add(param);
							}
							audioSfxIdIndex = -1;
						} else if (arg.type == ArgType.AUDIO_SFX_ID) {
							Expression param = params.next();
							audioSfxIdIndex = statement.size();	//Delayed replacement
							statement.add(param);
						} else if ("[( PARAMETERS )]".equals(sym.keyword)) {
							Expression param = params.next();
							if (!param.value.isEmpty()) {
								statement.add(new Expression("("+param+")"));
							}
						} else {
							Expression param = decompile(func, sym, params);
							if (param == null) {
								//Don't add implicit parameters
							} else if (arg.type == ArgType.OBJECT_FLOAT && sym.optional
									&& sym.keyword.replace("OBJECT", "0").equals("["+param+"]")) {
								param = null;	//Don't add missing OBJECT
							} else if (arg.type == ArgType.COORD && sym.optional) {
								boolean withPosition = params.next().boolVal();
								if (withPosition) {
									statement.add(param);
								} else {
									param = null;	//Don't add missing COORD_EXPR
								}
							} else if (arg.type == ArgType.BOOL && sym.optional) {
								throw new DecompileException("Undefined boolean option: "+sym.keyword, currentScript, ip, instructions.get(ip));
							} else {
								statement.add(param);
							}
						}
					}
				}
			} else {
				throw new DecompileException("Bad symbol: "+sym, currentScript, ip, instructions.get(ip));
			}
		}
		//
		if (statement.isEmpty()) {
			return null;
		}
		ListIterator<Expression> tokens = statement.listIterator();
		StringBuilder res = new StringBuilder();
		String prevToken = "(";
		while (tokens.hasNext()) {
			Expression part = tokens.next();
			if (part != null) {
				String token = part.toString();
				if (!token.isEmpty()) {
					char c0 = token.charAt(0);
					char c1 = prevToken.charAt(prevToken.length() - 1);
					if (c0 != ']' && c0 != '(' && c0 != ')' && c0 != ','
							&& c1 != '[' && c1 != '(') {
						res.append(" ");
					}
					res.append(token);
					prevToken = token;
				}
			}
		}
		return new Expression(res.toString());
	}
	
	private Expression decompile(NativeFunction func, ListIterator<Expression> params) {
		Argument arg = func.args[params.nextIndex()];
		Expression param = params.next();
		if (arg.type == ArgType.STRPTR && param.isNumber()) {
			int strptr = param.intVal();
			String string = chl.getDataSection().getString(strptr);
			return new Expression(escape(string));
		} else if (arg.type.isEnum && param.isNumber()) {
			int val = param.intVal();
			String alias = getSymbol(arg.type, val);
			return new Expression(alias, val);
		} else if (arg.type == ArgType.INT && param.isNumber()) {
			if (func == NativeFunction.RANDOM_ULONG && !typeContextStack.isEmpty()) {
				String contextType = getLast(typeContextStack);
				int val = param.intVal();
				String alias = getSymbol(contextType, val);
				return new Expression(alias, val);
			} else {
				return new Expression("constant "+param);
			}
		} else {
			return param;
		}
	}
	
	private static void skipNulls(ListIterator<?> iterator) {
		if (iterator.hasNext()) {
			Object item = iterator.next();
			while (item == null) {
				item = iterator.next();
			}
			iterator.previous();
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
			throw new DecompileException("No more instructions in code section", currentScript, it.previousIndex());
		}
		if (it.nextIndex() > currentScript.getLastInstructionAddress()) {
			throw new DecompileException("End of script found", currentScript, it.previousIndex());
		}
		Instruction instr = it.next();
		ip = it.previousIndex();
		stackDo(instr);
		trace(instr);
		return instr;
	}
	
	private Instruction prev() throws DecompileException {
		if (it.previousIndex() < currentScript.getInstructionAddress()) {
			throw new DecompileException("Begin of script found", currentScript, it.previousIndex());
		}
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
				throw new DecompileException(currentScript, it.previousIndex(), e);
			}
		} else if (instr.opcode == OPCode.CALL) {
			try {
				Script script = chl.getScriptsSection().getScript(instr.intVal);
				for (int i = 0; i < script.getParameterCount(); i++) {
					pop();
				}
			} catch (InvalidScriptIdException e) {
				throw new DecompileException(currentScript, it.previousIndex(), e);
			}
		} else if (instr.opcode == OPCode.SWAP && instr.dataType != DataType.INT) {
			StackVal val = getLast(stack);
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
						argc = pop(argcStack);
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
				throw new DecompileException(currentScript, it.previousIndex(), e);
			}
		} else if (instr.opcode == OPCode.CALL) {
			try {
				Script script = chl.getScriptsSection().getScript(instr.intVal);
				for (int i = 0; i < script.getParameterCount(); i++) {
					push(ArgType.UNKNOWN);
				}
			} catch (InvalidScriptIdException e) {
				throw new DecompileException(currentScript, it.previousIndex(), e);
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
			throw new DecompileException("Instruction address "+index+" is invalid", currentScript, it.previousIndex());
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
			throw new DecompileException("Expected "+opcode, currentScript, index, instr);
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
			throw new DecompileException("Expected "+opcode, currentScript, index, instr);
		}
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int flags, DataType type, float arg) throws DecompileException {
		if (instr.opcode != opcode
				|| instr.flags != flags
				|| (instr.dataType != type && type != null)
				|| instr.floatVal != arg) {
			throw new DecompileException("Expected "+opcode, currentScript, index, instr);
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
			throw new DecompileException("Cannot pop value from empty stack", currentScript, ip, instructions.get(ip));
		}
		return pop(stack);
	}
	
	private void decTabs() {
		tabs = tabs.substring(0, tabs.length() - 1);
		incTabs = false;
	}
	
	private String getVarName(int id) throws InvalidVariableIdException {
		return getVarName(id, true);
	}
	
	private String getVarName(int id, boolean withIndex) throws InvalidVariableIdException {
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
	
	
	private static <T> T getLast(List<T> list) {
		return list.get(list.size() - 1);
	}
	
	private static <T> T pop(List<T> stack) {
		return stack.remove(stack.size() - 1);
	}
	
	private static String escape(String string) {
		string = string.replace("\\", "\\\\");
		string = string.replace("\"", "\\\"");
		return "\"" + string + "\"";
	}
	
	private static String format(float v) {
		//return Integer.toHexString(Float.floatToRawIntBits(v));
		/*if (Math.abs(v) <= 16777216 && (int)v == v) {
			return String.valueOf((int)v);
		}*/
		decimalFormat.setMaximumFractionDigits(SIGNIFICANT_DIGITS - 1);
		String r = decimalFormat.format(v);
		int nInt = r.indexOf('.');	//Compute the number of int digits
		if (nInt > 1) {
			int nDec = Math.max(1, Math.min(SIGNIFICANT_DIGITS - nInt, SIGNIFICANT_DIGITS - 1));
			decimalFormat.setMaximumFractionDigits(nDec);
			r = decimalFormat.format(v);
		}
		return r;
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
		private final int intVal;
		private final float floatVal;
		private final boolean boolVal;
		private final Var var;
		public final boolean isExpression;
		
		public Expression(String value) {
			this(value, false, null, 0);
		}
		
		public Expression(int intVal) {
			this(null, false, DataType.INT, intVal);
		}
		
		public Expression(float floatVal) {
			this(null, false, DataType.FLOAT, floatVal);
		}
		
		public Expression(boolean boolVal) {
			this(null, false, DataType.BOOLEAN, boolVal);
		}
		
		public Expression(Var var) {
			this(null, false, null, var);
		}
		
		public Expression(String value, Var var) {
			this(value, false, null, var);
		}
		
		public Expression(String value, boolean lowPriority) {
			this(value, lowPriority, null, 0);
		}
		
		public Expression(String value, DataType type) {
			this(value, false, type, 0);
		}
		
		public Expression(String value, int intVal) {
			this(value, false, DataType.INT, intVal);
		}
		
		public Expression(String value, boolean lowPriority, DataType type, int intVal) {
			this.isExpression = value != null;
			this.value = isExpression ? value : String.valueOf(intVal);
			this.lowPriority = lowPriority;
			this.type = type;
			this.intVal = intVal;
			this.floatVal = 0f;
			this.boolVal = false;
			this.var = null;
		}
		
		public Expression(String value, boolean lowPriority, DataType type, float floatVal) {
			this.isExpression = value != null;
			this.value = isExpression ? value : format(floatVal);
			this.lowPriority = lowPriority;
			this.type = type;
			this.intVal = 0;
			this.floatVal = floatVal;
			this.boolVal = false;
			this.var = null;
		}
		
		public Expression(String value, boolean lowPriority, DataType type, boolean boolVal) {
			this.isExpression = value != null;
			this.value = isExpression ? value : String.valueOf(boolVal);
			this.lowPriority = lowPriority;
			this.type = type;
			this.intVal = 0;
			this.floatVal = 0f;
			this.boolVal = boolVal;
			this.var = null;
		}
		
		public Expression(String value, boolean lowPriority, DataType type, Var var) {
			this.isExpression = value != null;
			this.value = isExpression ? value : var.name;
			this.lowPriority = lowPriority;
			this.type = type;
			this.intVal = 0;
			this.floatVal = 0f;
			this.boolVal = false;
			this.var = var;
		}
		
		public boolean isNumber() {
			return type == DataType.FLOAT || type == DataType.INT;
		}
		
		public boolean isBool() {
			return type == DataType.BOOLEAN;
		}
		
		public boolean isVar() {
			return var != null;
		}
		
		public int intVal() {
			if (!isNumber()) {
				throw new RuntimeException("Not a number");
			}
			return intVal;
		}
		
		public float floatVal() {
			if (!isNumber()) {
				throw new RuntimeException("Not a number");
			}
			return floatVal;
		}
		
		public boolean boolVal() {
			if (!isBool()) {
				throw new RuntimeException("Not a bool");
			}
			return boolVal;
		}
		
		public Var getVar() {
			if (!isVar()) {
				throw new RuntimeException("Not a variable");
			}
			return var;
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
