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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import it.ld.bw.chl.exceptions.ParseError;
import it.ld.bw.chl.exceptions.ParseException;
import it.ld.bw.chl.exceptions.ScriptNotFoundException;
import it.ld.bw.chl.model.CHLFile;
import it.ld.bw.chl.model.DataType;
import it.ld.bw.chl.model.Header;
import it.ld.bw.chl.model.InitGlobal;
import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.NativeFunction;
import it.ld.bw.chl.model.OPCodeMode;
import it.ld.bw.chl.model.ObjectCode;
import it.ld.bw.chl.model.Script;
import it.ld.bw.chl.model.ScriptType;

import static it.ld.bw.chl.lang.Utils.*;

import static it.ld.bw.chl.model.NativeFunction.*;

public class CHLCompiler {
	private static final String DEFAULT_SOUNDBANK_NAME = "AUDIO_SFX_BANK_TYPE_IN_GAME";
	private static final String DEFAULT_SUBTYPE_NAME = "SCRIPT_FIND_TYPE_ANY";
	
	private static final Charset ASCII = Charset.forName("windows-1252");
	private static final int INITIAL_BUFFER_SIZE = 16 * 1024;
	
	private File file;
	private String sourceFilename;
	private LinkedList<SymbolInstance> symbols;
	private ListIterator<SymbolInstance> it;
	private int line;
	private int col;
	
	private Options options = new Options();
	
	private PrintStream out;
	
	private ObjectCode objcode = new ObjectCode();
	private CHLFile chl;
	private Script currentScript;
	private List<Instruction> instructions;
	private List<InitGlobal> initGlobals;
	private ByteArrayOutputStream dataBuffer = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
	private final LinkedHashMap<String, Integer> strings = new LinkedHashMap<>();
	private final Map<String, Integer> constants = new HashMap<>();
	private final LinkedHashMap<String, Var> localMap = new LinkedHashMap<>();
	private final Map<String, Integer> localConst = new HashMap<>();
	private final LinkedHashMap<String, Var> globalMap = new LinkedHashMap<>();
	private final Map<String, Script> scriptDefinitions = new HashMap<>();
	private final LinkedHashMap<String, ScriptToResolve> autoruns = new LinkedHashMap<>();
	private final List<ScriptToResolve> calls = new LinkedList<>();
	private String challengeName;
	private Integer challengeId;
	private boolean inCinemaBlock;
	private boolean inCameraBlock;
	private boolean inDialogueBlock;
	private boolean dontRequireEol = false;
	private boolean implicitVarDecl = false;
	
	private boolean noYield = false;
	private Set<String> externalVars = new LinkedHashSet<>();
	
	private ParseException lastParseException = null;
	
	private Map<String, String> properties = new HashMap<>();
	private Set<String> sourceDirs = new HashSet<>();
	
	public CHLCompiler() {
		this(System.out);
	}
	
	public CHLCompiler(PrintStream outStream) {
		this.out = outStream;
	}
	
	public Options getOptions() {
		return options;
	}
	
	public void setOptions(Options options) {
		this.options = options;
	}
	
	private void warning(String s) {
		out.println(s);
	}
	
	private void notice(String s) {
		if (options.verbose) {
			out.println(s);
		}
	}
	
	private void info(String s) {
		if (options.verbose) {
			out.println(s);
		}
	}
	
	private void convertToNodes(List<Token> tokens) throws ParseException {
		TokenType prevType = TokenType.EOL;
		symbols = new LinkedList<>();
		for (int pos = 0; pos < tokens.size(); pos++) {
			Token token = tokens.get(pos);
			if (token.type.important) {
				if (token.type != TokenType.EOL || prevType != TokenType.EOL) {
					symbols.add(toSymbol(pos, token));
					prevType = token.type;
				}
			}
		}
		if (prevType != TokenType.EOL) {
			symbols.add(new SymbolInstance(Syntax.getSymbol("EOL"), new Token(0, 0, TokenType.EOL)));
		}
		symbols.add(SymbolInstance.EOF);
	}
	
	public Map<String, Integer> getDefinedConstants() {
		return constants;
	}
	
	public void addConstants(Map<String, Integer> constants) {
		this.constants.putAll(constants);
	}
	
	public Set<String> getDefinedGlobalVars() {
		return externalVars;
	}
	
	public void addGlobalVars(Set<String> vars) {
		this.externalVars.addAll(vars);
	}
	
	public void loadHeader(File headerFile) throws FileNotFoundException, IOException, ParseException {
		info("loading "+headerFile.getName()+"...");
		CHeaderParser parser = new CHeaderParser();
		parser.parse(headerFile, constants);
	}
	
	public void loadInfo(File infoFile) throws FileNotFoundException, IOException, ParseException {
		info("loading "+infoFile.getName()+"...");
		InfoParser2 parser = new InfoParser2();
		parser.parse(infoFile, constants);
	}
	
	public ObjectCode compile(File file) throws IOException, ParseException {
		//Reinit
		objcode = new ObjectCode();
		chl = objcode.getChl();
		chl.header.setVersion(Header.BWCI);
		chl.taskVars.minItems = 0;	//Avoid wasting space
		currentScript = null;
		instructions = chl.code.getItems();
		strings.clear();
		dataBuffer.reset();
		initGlobals = chl.initGlobals.getItems();
		initGlobals.add(new InitGlobal("Null variable", 0));
		localMap.clear();
		localConst.clear();
		globalMap.clear();
		scriptDefinitions.clear();
		autoruns.clear();
		calls.clear();
		challengeName = null;
		challengeId = null;
		inCinemaBlock = false;
		inCameraBlock = false;
		inDialogueBlock = false;
		dontRequireEol = false;
		implicitVarDecl = false;
		lastParseException = null;
		properties = new HashMap<>();
		sourceDirs = new HashSet<>();
		//
		parse(file);
		//Finalize data section
		info("building data section...");
		if (options.debug) {
			for (Entry<String, String> p : properties.entrySet()) {
				storeStringData(p.getKey() + "=" + p.getValue());
			}
			if (!sourceDirs.isEmpty()) {
				storeStringData("source_dirs=" + String.join(";", sourceDirs));
			}
		}
		chl.data.setData(dataBuffer.toByteArray());
		//Resolve call and start instructions
		info("resolving call and start instructions...");
		for (ScriptToResolve call : calls) {
			try {
				Script script = chl.scripts.getScript(call.name);
				if (script.getParameterCount() != call.argc) {
					throw new ParseException("The number of parameters doesn't match script declaration", call.file, call.line, 1);
				}
				call.instr.intVal = script.getScriptID();
			} catch (ScriptNotFoundException e) {
				call.instr.intVal = -objcode.getExternalScriptId(call.name, call.argc);
			}
		}
		//Resolve auto start scripts
		info("resolving autorun scripts...");
		for (ScriptToResolve call : autoruns.values()) {
			try {
				Script script = chl.scripts.getScript(call.name);
				if (script.getParameterCount() > 0) {
					throw new ParseException("Script with parameters not valid for autorun: "+call.name, call.file, call.line, 1);
				}
				chl.autoStartScripts.getScripts().add(script.getScriptID());
			} catch (ScriptNotFoundException e) {
				int id = -objcode.getExternalScriptId(call.name, call.argc);
				chl.autoStartScripts.getScripts().add(id);
			}
		}
		//
		return objcode;
	}
	
	private void parse(File file) throws ParseException, IOException {
		try {
			this.file = file;
			sourceFilename = file.getName();
			info("compiling "+sourceFilename+"...");
			CHLLexer lexer = new CHLLexer();
			lexer.setExtendedSyntaxEnabled(options.extendedSyntax);
			List<Token> tokens = lexer.tokenize(file);
			parse(tokens);
			if (options.debug) {
				String dir = file.getAbsoluteFile().getParentFile().getAbsolutePath();
				sourceDirs.add(dir);
				long hash = crc32(file);
				properties.put("crc32["+sourceFilename+"]", String.format("%08X", hash));
			}
		} finally {
			file = null;
			sourceFilename = null;
		}
	}
	
	private void parse(List<Token> tokens) throws ParseException, IllegalStateException {
		convertToNodes(tokens);
		challengeName = null;
		challengeId = null;
		line = 0;
		col = 0;
		//
		it = symbols.listIterator();
		parseFile();
	}
	
	private SymbolInstance parseFile() throws ParseException {
		final int start = it.nextIndex();
		while (it.hasNext()) {
			SymbolInstance symbol = peek();
			if (symbol.is("challenge")) {
				parseChallenge();
			} else if (symbol.is("global")) {
				parseGlobal();
			} else if (symbol.is("define")) {
				parseDefine();
			} else if (symbol.is("run")) {
				parseAutorun();
			} else if (symbol.is("begin")) {
				parseScript();
			} else if (symbol.is("source")) {	// <- custom statement
				parseSource();
			} else if (symbol.is(TokenType.ANNOTATION)) {	// <- custom statement
				parseFileAnnotation();
			} else {
				break;
			}
		}
		SymbolInstance symbol = next();
		if (symbol != SymbolInstance.EOF) {
			throw new ParseException("Unexpected token: "+symbol+". Expected: EOF", file, symbol.token.line, symbol.token.col);
		}
		return replace(start, "FILE");
	}
	
	/**This is a custom statement that permits to compile from merged source files.
	 * @return
	 * @throws ParseException
	 */
	private SymbolInstance parseSource() throws ParseException {
		final int start = it.nextIndex();
		accept("source");
		SymbolInstance symbol = accept(TokenType.STRING);
		sourceFilename = symbol.token.stringVal();
		accept(TokenType.EOL);
		info("Source filename set to: "+sourceFilename);
		return replace(start, "source STRING EOL");
	}
	
	private SymbolInstance parseFileAnnotation() throws ParseException {
		final int start = it.nextIndex();
		String text = accept(TokenType.ANNOTATION).toString().trim().substring(3);	//Get rid of //@
		String[] tokens = text.split(" ");
		String cls = tokens[0];
		if ("extern".equals(cls)) {
			if (tokens.length != 2) {
				throw new ParseError("@extern expects 1 parameter", file, line, col);
			}
			externalVars.add(tokens[1]);
		} else {
			throw new ParseError("Unknown annotation: "+cls, file, line, col);
		}
		return replace(start, "ANNOTATION");
	}
	
	private SymbolInstance parseChallenge() throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = parse("challenge IDENTIFIER EOL")[1];
		challengeName = symbol.token.value;
		challengeId = getConstant("CHALLENGE_" + challengeName);
		if (challengeId == -1) {
			notice("NOTICE: challenge id "+challengeName+" is dummy, snapshot and highlight statements "
					+ "won't be available. At "+file.getName()+":"+line+":"+col);
		}
		return replace(start, "challenge IDENTIFIER EOL");
	}
	
	private void declareGlobalVar(String name, int size, float val) {
		Var var = globalMap.get(name);
		if (var == null) {
			List<String> chlVars = chl.globalVars.getNames();
			List<InitGlobal> chlInits = initGlobals;
			chlVars.add(name);
			chlInits.add(new InitGlobal(DataType.FLOAT, name, val));
			int varId = chlVars.size();
			for (int i = 1; i < size; i++) {
				chlVars.add("LHVMA");
				chlInits.add(new InitGlobal(DataType.FLOAT, "LHVMA", 0));
			}
			//
			var = new Var(null, name, varId, size, val);
			globalMap.put(name, var);
			externalVars.add(name);
		} else {
			throw new ParseError("Redeclaration of global var "+name, file, line, col);
		}
	}
	
	private SymbolInstance parseGlobal() throws ParseException {
		final int start = it.nextIndex();
		accept("global");
		SymbolInstance symbol = peek();
		if (symbol.is("constant")) {
			//global constant IDENTIFIER = CONSTANT
			symbol = parse("constant IDENTIFIER =")[1];
			String name = symbol.token.value;
			symbol = next();
			int val;
			if (symbol.is(TokenType.NUMBER) || symbol.is(TokenType.IDENTIFIER)) {
				val = getConstant(symbol);
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: CONSTANT", lastParseException, file, symbol.token.line, symbol.token.col);
			}
			accept(TokenType.EOL);
			Integer oldVal = constants.put(name, val);
			if (oldVal != null && oldVal != val) {
				warning("WARNING: redefinition of global constant: "+name+" at "+file+":"+symbol.token.line);
			}
			return replace(start, "GLOBAL_CONST_DECL");
		} else {
			accept(TokenType.IDENTIFIER);
			String name = symbol.token.value;
			int count = 1;
			float val = 0;
			symbol = peek(false);
			if (symbol.is("=")) {
				//global IDENTIFIER = NUMBER
				accept("=");
				symbol = accept(TokenType.NUMBER);
				val = symbol.token.floatVal();
				accept(TokenType.EOL);
			} else if (symbol.is("[")) {
				//global IDENTIFIER\[CONSTANT\]
				accept("[");
				symbol = acceptAny(TokenType.NUMBER, TokenType.IDENTIFIER);
				count = getConstant(symbol);
				accept("]");
				accept(TokenType.EOL);
			} else {
				//global IDENTIFIER
				accept(TokenType.EOL);
			}
			declareGlobalVar(name, count, val);
			return replace(start, "GLOBAL_VAR_DECL");
		}
	}
	
	private void define(ScriptType type, String name, int parameterCount) {
		Script def = scriptDefinitions.get(name);
		if (def != null) {
			if (def.getScriptType() != type || !def.getName().equals(name) || def.getParameterCount() != parameterCount) {
				throw new ParseError("Redefinition of script "+name, file, line, col);
			}
		} else {
			def = new Script(chl);
			def.setScriptType(type);
			def.setName(name);
			def.setParameterCount(parameterCount);
			scriptDefinitions.put(name, def);
		}
	}
	
	private SymbolInstance parseDefine() throws ParseException {
		final int start = it.nextIndex();
		//define SCRIPT_TYPE IDENTIFIER[([ARGS])] EOL
		accept("define");
		localMap.clear();
		SymbolInstance symbol = parseScriptType();
		ScriptType type = ScriptType.fromKeyword(symbol.toString());
		symbol = accept(TokenType.IDENTIFIER);
		String name = symbol.token.value;
		symbol = peek();
		int argc = 0;
		if (symbol.is("(")) {
			argc = parseArguments(false);
		}
		accept(TokenType.EOL);
		define(type, name, argc);
		return replace(start, "DEFINE");
	}
	
	private SymbolInstance parseAutorun() throws ParseException {
		final int start = it.nextIndex();
		//run script IDENTIFIER
		SymbolInstance symbol = parse("run script IDENTIFIER EOL")[2];
		String name = symbol.token.value;
		ScriptToResolve toResolve = new ScriptToResolve(file, line, null, name, 0);
		if (autoruns.put(name, toResolve) != null) {
			throw new ParseException("Duplicate autorun definition: "+name, file, symbol.token.line, symbol.token.col);
		}
		return replace(start, "run script IDENTIFIER EOL");
	}
	
	private SymbolInstance parseScript() throws ParseException {
		final int start = it.nextIndex();
		localMap.clear();
		localConst.clear();
		try {
			final Script script = new Script(chl);
			currentScript = script;
			script.setScriptID(chl.scripts.getItems().size() + 1);
			script.setGlobalCount(chl.globalVars.getNames().size());
			script.setSourceFilename(sourceFilename);
			script.setInstructionAddress(getIp());
			accept("begin");
			SymbolInstance scriptTypeSymbol = parseScriptType();
			ScriptType scriptType = ScriptType.fromKeyword(scriptTypeSymbol.toString());
			script.setScriptType(scriptType);
			SymbolInstance symbol = accept(TokenType.IDENTIFIER);
			String name = symbol.token.value;
			script.setName(name);
			int argc = 0;
			symbol = peek();
			if (symbol.is("(")) {
				argc = parseArguments(true);
			}
			if (options.returnEnabled && script.getScriptType() == ScriptType.FUNCTION) {
				addParameter("_retval", true);
				argc++;
			}
			script.setParameterCount(argc);
			define(scriptType, name, argc);
			//Start the exception handler and load parameter values from the stack
			Instruction except_lblExceptionHandler = except();
			Iterator<String> lvars = localMap.keySet().iterator();
			for (int i = 0; i < argc; i++) {
				String var = lvars.next();
				popf(var);
			}
			//
			if (options.returnEnabled) {
				addLocalVar("_returned");
			}
			symbol = peek();
			if (!symbol.is("start")) {
				parseLocals();
			}
			//Check for #noyield
			symbol = peek();
			if (symbol.is(TokenType.ANNOTATION) && "//@noyield".equals(symbol.toString().trim())) {
				noYield = true;
				next();
			}
			parse("start EOL");
			if (noYield || scriptType == ScriptType.FUNCTION) {
				noYield = false;
			} else {
				free();
			}
			chl.scripts.getItems().add(script);
			//STATEMENTS
			parseStatements();
			//EXCEPTIONS
			endexcept();
			Instruction jmp_lblEnd = jmp();
			except_lblExceptionHandler.intVal = getIp();
			parseExceptions();
			int lblEnd = getIp();
			jmp_lblEnd.intVal = lblEnd;
			//
			try {
				if (options.returnEnabled && script.getScriptType() == ScriptType.FUNCTION) {
					parse("end function");
				} else {
					parse("end script");
				}
				end();
			} catch (ParseException e) {
				symbol = peek();
				throw new ParseException("Unrecognized statement", file, symbol.token.line, symbol.token.col);
			}
			symbol = accept(TokenType.IDENTIFIER);
			if (!symbol.token.value.equals(name)) {
				throw new ParseException("The script name at \"end script\" must match the one at \"begin script\"", file, symbol.token.line, symbol.token.col);
			}
			accept(TokenType.EOL);
			return replace(start, "SCRIPT");
		} finally {
			localMap.clear();
			localConst.clear();
		}
	}
	
	private SymbolInstance parseScriptType() throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = next();
		if (symbol.is("help")) {
			accept("script");
			return replace(start, "help script");
		} else if (symbol.is("challenge")) {
			parse("help script");
			return replace(start, "challenge help script");
		} else if (symbol.is("temple")) {
			symbol = next();
			if (symbol.is("help")) {
				accept("script");
				return replace(start, "temple help script");
			} else if (symbol.is("special")) {
				accept("script");
				return replace(start, "temple special script");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("multiplayer")) {
			parse("help script");
			return replace(start, "multiplayer help script");
		} else if (symbol.is("script")) {
			return replace(start, "script");
		} else if (options.returnEnabled && symbol.is("function")) {
			return replace(start, "function");
		} else {
			throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private int parseArguments(boolean addToLocalVars) throws ParseException {
		final int start = it.nextIndex();
		int argc = 0;
		accept("(");
		SymbolInstance symbol = peek();
		if (!symbol.is(")")) {
			symbol = next();
			boolean ref = symbol.is("*");
			if (ref) symbol = accept(TokenType.IDENTIFIER);
			String name = symbol.token.value;
			argc++;
			if (addToLocalVars) {
				addParameter(name, ref);
			}
			symbol = peek();
			while (!symbol.is(")")) {
				accept(",");
				symbol = next();
				ref = symbol.is("*");
				if (ref) symbol = accept(TokenType.IDENTIFIER);
				name = symbol.token.value;
				argc++;
				if (addToLocalVars) {
					addParameter(name, ref);
				}
				symbol = peek();
			}
		}
		accept(")");
		replace(start, "(ARGS)");
		return argc;
	}
	
	private int parseParameters() throws ParseException {
		final int start = it.nextIndex();
		int argc = 0;
		accept("(");
		SymbolInstance symbol = peek();
		if (!symbol.is(")")) {
			parseParameter();
			argc++;
			symbol = peek();
			while (!symbol.is(")")) {
				accept(",");
				parseParameter();
				argc++;
				symbol = peek();
			}
		}
		accept(")");
		replace(start, "(PARAMETERS)");
		return argc;
	}
	
	private SymbolInstance parseParameter() throws ParseException {
		SymbolInstance symbol = parseExpression(false);
		if (symbol != null) {
			return symbol;
		}
		symbol = parseObject(false);
		if (symbol != null) {
			return symbol;
		}
		symbol = peek();
		//TODO verify the need to handle CONST_EXPR and CONDITION
		throw new ParseException("Expected: EXPRESSION|OBJECT", lastParseException, file, line, col);
	}
	
	private SymbolInstance parseLocals() throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		while (symbol.is(TokenType.IDENTIFIER) || symbol.is("constant")) {
			parseLocal();
			symbol = peek();
		}
		return replace(start, "{LOCAL_DECL}");
	}
	
	private Var addLocalVar(String name) throws ParseException {
		return addLocalVar(name, 1, false, false);
	}
	
	private Var addParameter(String name, boolean ref) throws ParseException {
		return addLocalVar(name, 1, true, ref);
	}
	
	private Var addLocalVar(String name, int size) throws ParseException {
		return addLocalVar(name, size, false, false);
	}
	
	private Var addLocalVar(String name, int size, boolean isArg, boolean ref) throws ParseException {
		if (localMap.containsKey(name)) {
			throw new ParseException("Duplicate local variable: "+name, file, line, col);
		} else if (globalMap.containsKey(name)) {
			throw new ParseException("A global variable with the same name exists: "+name, file, line, col);
		}
		List<String> scriptVars = currentScript.getVariables();
		scriptVars.add(name);
		int id = currentScript.getGlobalCount() + scriptVars.size();
		for (int i = 1; i < size; i++) {
			scriptVars.add("LHVMA");
		}
		if (ref) {
			currentScript.setReference(name);
		}
		Var var = new Var(currentScript, name, id, size, 0, isArg, ref);
		localMap.put(name, var);
		return var;
	}
	
	private SymbolInstance parseLocal() throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = next();
		if (symbol.is(TokenType.IDENTIFIER)) {
			String name = symbol.token.value;
			symbol = peek(false);
			if (symbol.is("=")) {
				//IDENTIFIER = EXPRESSION
				accept("=");
				symbol = parseExpression(false);
				if (symbol == null) {
					symbol = parseObject(false);
					if (symbol == null) {
						symbol = peek();
						throw new ParseException("Expected: EXPRESSION|OBJECT", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				}
				accept(TokenType.EOL);
				addLocalVar(name);
				popf(name);
			} else if (symbol.is("[")) {
				//IDENTIFIER[CONSTANT]
				accept("[");
				symbol = acceptAny(TokenType.NUMBER, TokenType.IDENTIFIER);
				int size = getConstant(symbol);
				accept("]");
				accept(TokenType.EOL);
				addLocalVar(name, size);
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: =|[", lastParseException, file, symbol.token.line, symbol.token.col);
			}
			return replace(start, "LOCAL_DECL");
		} else if (symbol.is("constant")) {
			//constant IDENTIFIER = CONSTANT
			parse("IDENTIFIER =");
			String constant = symbol.token.value;
			if (localConst.containsKey(constant)) {
				throw new ParseException("Duplicate constant: "+constant, file, symbol.token.line, symbol.token.col);
			}
			symbol = next();
			if (symbol.is(TokenType.NUMBER) || symbol.is(TokenType.IDENTIFIER)) {
				int val = getConstant(symbol);
				localConst.put(constant, val);
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: CONSTANT", lastParseException, file, symbol.token.line, symbol.token.col);
			}
			accept(TokenType.EOL);
			return replace(start, "CONST_DECL");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: IDENTIFIER|constant", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseStatements() throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = parseStatement();
		while (symbol != null) {
			symbol = parseStatement();
		}
		return replace(start, "STATEMENTS");
	}
	
	private SymbolInstance parseStatement() throws ParseException {
		noYield = false;
		//
		SymbolInstance symbol = peek();
		if (symbol.is(TokenType.ANNOTATION)) {
			if ("//@noyield".equals(symbol.toString().trim())) {
				noYield = true;
				next();
				symbol = peek();
			}
		}
		//
		if (symbol.is("challenge")) {
			return parseChallenge();
		} else if (symbol.is("remove")) {
			return parseRemove();
		} else if (symbol.is("add")) {
			return parseAdd();
		} else if (symbol.is("move")) {
			return parseMove();
		} else if (symbol.is("set")) {
			return parseSet();
		} else if (symbol.is("delete")) {
			return parseDelete();
		} else if (symbol.is("release")) {
			return parseRelease();
		} else if (symbol.is("enable") || symbol.is("disable")) {
			return parseEnableDisable();
		} else if (symbol.is("open") || symbol.is("close")) {
			return parseOpenClose();
		} else if (symbol.is("teach")) {
			return parseTeach();
		} else if (symbol.is("force")) {
			return parseForce();
		} else if (symbol.is("initialise")) {
			return parseInitialise();
		} else if (symbol.is("clear")) {
			return parseClear();
		} else if (symbol.is("attach")) {
			return parseAttach();
		} else if (symbol.is("toggle")) {
			return parseToggle();
		} else if (symbol.is("detach")) {
			return parseDetach();
		} else if (symbol.is("swap")) {
			return parseSwap();
		} else if (symbol.is("queue")) {
			return parseQueue();
		} else if (symbol.is("pause") || symbol.is("unpause")) {
			return parsePauseUnpause();
		} else if (symbol.is("load")) {
			return parseLoad();
		} else if (symbol.is("save")) {
			return parseSave();
		} else if (symbol.is("stop")) {
			return parseStop();
		} else if (symbol.is("start")) {
			return parseStart();
		} else if (symbol.is("disband")) {
			return parseDisband();
		} else if (symbol.is("populate")) {
			return parsePopulate();
		} else if (symbol.is("affect")) {
			return parseAffect();
		} else if (symbol.is("snapshot")) {
			return parseSnapshot();
		} else if (symbol.is("update")) {
			return parseUpdate();
		} else if (symbol.is("build")) {
			return parseBuild();
		} else if (symbol.is("run")) {
			return parseRun();
		} else if (symbol.is("wait")) {
			return parseWait();
		} else if (symbol.is("enter") || symbol.is("exit")) {
			return parseEnterExit();
		} else if (symbol.is("restart")) {
			return parseRestart();
		} else if (symbol.is("state")) {
			return parseState();
		} else if (symbol.is("make")) {
			return parseMake();
		} else if (symbol.is("eject")) {
			return parseEject();
		} else if (symbol.is("send")) {
			return parseSend();
		} else if (symbol.is("say")) {
			return parseSay();
		} else if (symbol.is("draw")) {
			return parseDraw();
		} else if (symbol.is("fade")) {
			return parseFade();
		} else if (symbol.is("store")) {
			return parseStore();
		} else if (symbol.is("restore")) {
			return parseRestore();
		} else if (symbol.is("reset")) {
			return parseReset();
		} else if (symbol.is("camera")) {
			return parseStatementCamera();
		} else if (symbol.is("shake")) {
			return parseShake();
		} else if (symbol.is("fire")) {
			return parseFire();
		} else if (symbol.is("if")) {
			return parseIf();
		} else if (symbol.is("while")) {
			return parseWhile();
		} else if (symbol.is("begin")) {
			return parseBegin();
		} else if (symbol.is(TokenType.IDENTIFIER)) {
			if (checkAhead("ANY play") || checkAhead("ANY [ ANY ] play")) {
				return parseObjectPlay();
			} else {
				return parseAssignment();
			}
		} else if (options.extendedSyntax) {
			if (symbol.is("for")) {
				return parseFor();
			}
		} else if (options.returnEnabled) {
			if (symbol.is("return")) {
				return parseReturn();
			}
		}
		lastParseException = new ParseException("Unexpected token: "+symbol+". Expected STATEMENT", lastParseException, file, line, col);
		return null;
	}
	
	private SymbolInstance parseFor() throws ParseException {
		final int start = it.nextIndex();
		List<Instruction> iterStatement = new LinkedList<>();
		//for STATEMENT ; CONDITION ; STATEMENT
		dontRequireEol = true;
		implicitVarDecl = true;
		parse("for STATEMENT ;");	//Init statement
		implicitVarDecl = false;
		dontRequireEol = false;
		int lblCheckCond = getIp();
		parse("CONDITION ;");		//Iteration condition
		Instruction jz_lblEndFor = jz();
		final int pos = instructions.size();
		parseStatement();			//Post iteration statement
		//
		while (instructions.size() > pos) {
			Instruction instr = instructions.remove(instructions.size() - 1);
			iterStatement.add(0, instr);
		}
		//STATEMENTS
		parseStatements();
		instructions.addAll(iterStatement);
		parse("end for EOL");
		jmp(lblCheckCond)
		.mode = OPCodeMode.FORWARD;	//Avoid yielding
		int lblEndFor = getIp();
		jz_lblEndFor.intVal = lblEndFor;
		return replace(start, "FOR");
	}
	
	private SymbolInstance parseReturn() throws ParseException {
		final int start = it.nextIndex();
		accept("return");
		pushvVal("_retval");
		pushf(0);
		SymbolInstance symbol = parseExpression(false);
		if (symbol != null) {
			//Leave the value on the stack
		} else {
			symbol = parseObject(false);
			if (symbol != null) {
				//Leave the object on the stack
			} else {
				symbol = peek();
				throw new ParseException("Unexpected token: "+symbol+". Expected EXPRESSION|OBJECT", file, symbol.token.line, symbol.token.col);
			}
		}
		ref_and_offset_pop();
		accept(TokenType.EOL);
		//TODO should "jump" to the end of function (handling the exception stack)
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseObjectPlay() throws ParseException {
		final int start = it.nextIndex();
		//OBJECT play CONST_EXPR [loop EXPRESSION]
		parse("VARIABLE play CONST_EXPR [loop EXPRESSION] EOL", 1);
		sys(THING_PLAY_ANIM);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseRemove() throws ParseException {
		final int start = it.nextIndex();
		//remove resource CONST_EXPR EXPRESSION from OBJECT
		parse("remove resource CONST_EXPR EXPRESSION from OBJECT EOL");
		sys(REMOVE_RESOURCE);
		popf();
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseAdd() throws ParseException {
		final int start = it.nextIndex();
		accept("add");
		SymbolInstance symbol = peek();
		if (symbol.is("for")) {
			//add for building OBJECT to OBJECT
			parse("for building OBJECT to OBJECT EOL");
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else if (symbol.is("resource")) {
			//add resource CONST_EXPR EXPRESSION to OBJECT
			parse("resource CONST_EXPR EXPRESSION to OBJECT EOL");
			sys(ADD_RESOURCE);
			popf();
			return replace(start, "STATEMENT");
		} else {
			parse("OBJECT target");
			symbol = next();
			if (symbol.is("at")) {
				parse("COORD_EXPR EOL");
				//add OBJECT target at COORD_EXPR
				sys(ADD_SPOT_VISUAL_TARGET_POS);
				return replace(start, "STATEMENT");
			} else if (symbol.is("on")) {
				parse("OBJECT EOL");
				//add OBJECT target on OBJECT
				sys(ADD_SPOT_VISUAL_TARGET_OBJECT);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		}
	}

	private SymbolInstance parseMove() throws ParseException {
		final int start = it.nextIndex();
		accept("move");
		SymbolInstance symbol = peek();
		if (symbol.is("computer")) {
			parse("computer player EXPRESSION to COORD_EXPR speed EXPRESSION [with fixed height] EOL");
			//move computer player EXPRESSION to COORD_EXPR speed EXPRESSION [with fixed height]
			sys(MOVE_COMPUTER_PLAYER_POSITION);
			return replace(start, "STATEMENT");
		} else if (symbol.is("game")) {
			parse("game time EXPRESSION time EXPRESSION EOL");
			//move game time EXPRESSION time EXPRESSION
			sys(MOVE_GAME_TIME);
			return replace(start, "STATEMENT");
		} else if (symbol.is("music")) {
			parse("music from OBJECT to OBJECT EOL");
			//move music from OBJECT to OBJECT
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else if (symbol.is("camera")) {
			accept("camera");
			symbol = peek();
			if (symbol.is("position")) {
				accept("position");
				symbol = peek();
				if (symbol.is("to")) {
					parse("to COORD_EXPR time EXPRESSION EOL");
					//move camera position to COORD_EXPR time EXPRESSION
					sys(MOVE_CAMERA_POSITION);
					return replace(start, "STATEMENT");
				} else if (symbol.is("follow")) {
					parse("follow OBJECT EOL");
					//move camera position follow OBJECT
					sys(POSITION_FOLLOW);
					return replace(start, "STATEMENT");
				} else {
					parse("COORD_EXPR docus COORD_EXPR lens EXPRESSION time EXPRESSION EOL");
					//move camera position COORD_EXPR focus COORD_EXPR lens EXPRESSION time EXPRESSION
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				}
			} else if (symbol.is("focus")) {
				accept("focus");
				symbol = peek();
				if (symbol.is("to")) {
					parse("to COORD_EXPR time EXPRESSION EOL");
					//move camera focus to COORD_EXPR time EXPRESSION
					sys(MOVE_CAMERA_FOCUS);
					return replace(start, "STATEMENT");
				} else if (symbol.is("follow")) {
					parse("follow OBJECT EOL");
					//move camera focus follow OBJECT
					sys(FOCUS_FOLLOW);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("to")) {
				accept("to");
				symbol = peek();
				if (symbol.is("face")) {
					parse("face OBJECT distance EXPRESSION time EXPRESSION EOL");
					//move camera to face OBJECT distance EXPRESSION time EXPRESSION
					sys(MOVE_CAMERA_TO_FACE_OBJECT);
					return replace(start, "STATEMENT");
				} else {
					//move camera to IDENTIFIER CONSTANT time EXPRESSION
					accept(TokenType.IDENTIFIER);
					symbol = accept(TokenType.IDENTIFIER);
					String camEnum = symbol.token.value;
					int constVal = getConstant(camEnum);
					pushi(constVal);
					sys(CONVERT_CAMERA_FOCUS);
					pushi(constVal);
					sys(CONVERT_CAMERA_POSITION);
					parse("time EXPRESSION EOL");
					swapf(4);
					sys(MOVE_CAMERA_POSITION);
					sys(MOVE_CAMERA_FOCUS);
					return replace(start, "STATEMENT");
				}
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else {
			parse("OBJECT position to COORD_EXPR [radius EXPRESSION] EOL");
			//move OBJECT position to COORD_EXPR [radius EXPRESSION]
			sys(MOVE_GAME_THING);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseSet() throws ParseException {
		final int start = it.nextIndex();
		accept("set");
		SymbolInstance symbol = peek();
		if (symbol.is("player")) {
			parse("player EXPRESSION");
			symbol = peek();
			if (symbol.is("creature")) {
				//set player EXPRESSION creature to OBJECT
				parse("creature to OBJECT EOL");
				sys(CREATURE_SET_PLAYER);
				return replace(start, "STATEMENT");
			} else {
				//set player EXPRESSION ally with player EXPRESSION percentage EXPRESSION
				parse("ally with player EXPRESSION percentage EXPRESSION EOL");
				sys(SET_PLAYER_ALLY);
				return replace(start, "STATEMENT");
			}
		} else if (symbol.is("computer")) {
			parse("computer player EXPRESSION");
			symbol = peek();
			if (symbol.is("position")) {
				parse("position to COORD_EXPR [with fixed height] EOL");
				//set computer player EXPRESSION position to COORD_EXPR [with fixed height]
				sys(SET_COMPUTER_PLAYER_POSITION);
				return replace(start, "STATEMENT");
			} else if (symbol.is("personality")) {
				parse("personality STRING EXPRESSION EOL");
				//set computer player EXPRESSION personality STRING EXPRESSION
				sys(SET_COMPUTER_PLAYER_PERSONALITY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("suppression")) {
				parse("suppression STRING EXPRESSION EOL");
				//set computer player EXPRESSION suppression STRING EXPRESSION
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("speed")) {
				parse("speed EXPRESSION EOL");
				//set computer player EXPRESSION speed EXPRESSION
				sys(SET_COMPUTER_PLAYER_SPEED);
				return replace(start, "STATEMENT");
			} else if (symbol.is("attitude")) {
				parse("attitude to player EXPRESSION to EXPRESSION EOL");
				//set computer player EXPRESSION attitude to player EXPRESSION to EXPRESSION
				sys(SET_COMPUTER_PLAYER_ATTITUDE);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("game")) {
			accept("game");
			symbol = peek();
			if (symbol.is("time")) {
				accept("time");
				symbol = peek();
				if (symbol.is("properties")) {
					parse("properties duration EXPRESSION percentage night EXPRESSION percentage dawn dusk EXPRESSION EOL");
					//set game time properties duration EXPRESSION percentage night EXPRESSION percentage dawn dusk EXPRESSION
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else {
					parse("EXPRESSION EOL");
					//set game time EXPRESSION
					sys(SET_GAME_TIME);
					return replace(start, "STATEMENT");
				}
			} else if (symbol.is("speed")) {
				//set game speed to EXPRESSION
				parse("speed to EXPRESSION EOL");
				sys2(SET_GAMESPEED);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("interaction")) {
			parse("interaction CONST_EXPR EOL");
			//set interaction CONST_EXPR
			sys2(SET_INTERFACE_INTERACTION);
			return replace(start, "STATEMENT");
		} else if (symbol.is("fade")) {
			accept("fade");
			symbol = peek();
			if (symbol.is("red")) {
				parse("red EXPRESSION green EXPRESSION blue EXPRESSION time EXPRESSION EOL");
				//set fade red EXPRESSION green EXPRESSION blue EXPRESSION time EXPRESSION
				sys(SET_FADE);
				return replace(start, "STATEMENT");
			} else if (symbol.is("in")) {
				parse("in time EXPRESSION EOL");
				//set fade in time EXPRESSION
				sys(SET_FADE_IN);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("bookmark")) {
			parse("bookmark EXPRESSION to COORD_EXPR EOL");
			//set bookmark EXPRESSION to COORD_EXPR
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else if (symbol.is("draw")) {
			parse("draw text colour red EXPRESSION green EXPRESSION blue EXPRESSION EOL");
			//set draw text colour red EXPRESSION green EXPRESSION blue EXPRESSION
			sys(SET_DRAW_TEXT_COLOUR);
			return replace(start, "STATEMENT");
		} else if (symbol.is("clipping")) {
			parse("clipping window across EXPRESSION down EXPRESSION width EXPRESSION height EXPRESSION time EXPRESSION EOL");
			//set clipping window across EXPRESSION down EXPRESSION width EXPRESSION height EXPRESSION time EXPRESSION
			sys(SET_CLIPPING_WINDOW);
			return replace(start, "STATEMENT");
		} else if (symbol.is("camera")) {
			accept("camera");
			symbol = peek();
			if (symbol.is("zones")) {
				parse("zones to STRING EOL");
				//set camera zones to STRING
				sys(SET_CAMERA_ZONE);
				return replace(start, "STATEMENT");
			} else if (symbol.is("lens")) {
				parse("lens EXPRESSION [time EXPRESSION] EOL");
				//set camera lens EXPRESSION [time EXPRESSION]
				sys(MOVE_CAMERA_LENS);
				return replace(start, "STATEMENT");
			} else if (symbol.is("position")) {
				accept("position");
				symbol = peek();
				if (symbol.is("to")) {
					parse("to COORD_EXPR EOL");
					//set camera position to COORD_EXPR
					sys(SET_CAMERA_POSITION);
					return replace(start, "STATEMENT");
				} else if (symbol.is("follow")) {
					accept("follow");
					symbol = peek();
					if (symbol.is("computer")) {
						parse("computer player EXPRESSION EOL");
						//set camera position follow computer player EXPRESSION
						sys(SET_POSITION_FOLLOW_COMPUTER_PLAYER);
						return replace(start, "STATEMENT");
					} else {
						parse("OBJECT EOL");
						//set camera position follow OBJECT
						sys(SET_POSITION_FOLLOW);
						return replace(start, "STATEMENT");
					}
				} else {
					parse("COORD_EXPR focus COORD_EXPR lens EXPRESSION EOL");
					//set camera position COORD_EXPR focus COORD_EXPR lens EXPRESSION
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				}
			} else if (symbol.is("focus")) {
				accept("focus");
				symbol = peek();
				if (symbol.is("to")) {
					parse("to COORD_EXPR EOL");
					//set camera focus to COORD_EXPR
					sys(SET_CAMERA_FOCUS);
					return replace(start, "STATEMENT");
				} else if (symbol.is("follow")) {
					accept("follow");
					symbol = peek();
					if (symbol.is("computer")) {
						parse("computer player EXPRESSION EOL");
						//set camera focus follow computer player EXPRESSION
						sys(SET_FOCUS_FOLLOW_COMPUTER_PLAYER);
						return replace(start, "STATEMENT");
					} else {
						parse("OBJECT EOL");
						//set camera focus follow OBJECT
						sys(SET_FOCUS_FOLLOW);
						return replace(start, "STATEMENT");
					}
				} else {
					throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("to")) {
				accept("to");
				symbol = peek();
				if (symbol.is("face")) {
					parse("face OBJECT distance EXPRESSION EOL");
					//set camera to face OBJECT distance EXPRESSION
					sys(SET_CAMERA_TO_FACE_OBJECT);
					return replace(start, "STATEMENT");
				} else {
					//set camera to IDENTIFIER CONSTANT
					symbol = parse("IDENTIFIER IDENTIFIER EOL")[1];
					String camEnum = symbol.token.value;
					int constVal = getConstant(camEnum);
					pushi(constVal);
					sys(CONVERT_CAMERA_FOCUS);
					pushi(constVal);
					sys(CONVERT_CAMERA_POSITION);
					sys(SET_CAMERA_POSITION);
					sys(SET_CAMERA_FOCUS);
					return replace(start, "STATEMENT");
				}
			} else if (symbol.is("follow")) {
				parse("follow OBJECT distance EXPRESSION EOL");
				//set camera follow OBJECT distance EXPRESSION
				sys(SET_FOCUS_AND_POSITION_FOLLOW);
				return replace(start, "STATEMENT");
			} else if (symbol.is("properties")) {
				parse("properties distance EXPRESSION speed EXPRESSION angle EXPRESSION enable|disable behind EOL");
				//set camera properties distance EXPRESSION speed EXPRESSION angle EXPRESSION enable|disable behind
				sys(CAMERA_PROPERTIES);
				return replace(start, "STATEMENT");
			} else if (symbol.is("auto")) {
				parse("auto track OBJECT distance EXPRESSION EOL");
				//set camera auto track OBJECT distance EXPRESSION
				sys(SET_CAMERA_AUTO_TRACK);
				return replace(start, "STATEMENT");
			} else if (symbol.is("heading")) {
				parse("heading follow OBJECT distance EXPRESSION EOL");
				//set camera heading follow OBJECT distance EXPRESSION
				sys(SET_CAMERA_HEADING_FOLLOW);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("dual")) {
			parse("dual camera to OBJECT OBJECT EOL");
			//set dual camera to OBJECT OBJECT
			sys(UPDATE_DUAL_CAMERA);
			return replace(start, "STATEMENT");
		} else if (symbol.is("cannon")) {
			parse("cannon strength EXPRESSION EOL");
			//set cannon strength EXPRESSION
			sys(SET_CANNON_STRENGTH);
			return replace(start, "STATEMENT");
		} else if (symbol.is("gun")) {
			parse("gun OBJECT angle EXPRESSION pitch EXPRESSION EOL");
			//set gun OBJECT angle EXPRESSION pitch EXPRESSION
			sys(GUN_ANGLE_PITCH);
			return replace(start, "STATEMENT");
		} else if (symbol.is("countdown")) {
			parse("countdown timer draw CONST_EXPR EOL");
			//set countdown timer draw CONST_EXPR
			sys(SET_COUNTDOWN_TIMER_DRAW);
			return replace(start, "STATEMENT");
		} else if (symbol.is("dolphin")) {
			accept("dolphin");
			symbol = peek();
			if (symbol.is("speed")) {
				parse("speed EXPRESSION EOL");
				//set dolphin speed EXPRESSION
				sys(SET_DOLPHIN_SPEED);
				return replace(start, "STATEMENT");
			} else if (symbol.is("wait")) {
				parse("wait EXPRESSION EOL");
				//set dolphin wait EXPRESSION
				sys(SET_DOLPHIN_WAIT);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected speed|wait", file, symbol.token.line, symbol.token.col);
			}
		} else {
			symbol = parseObject(false);
			if (symbol != null) {
				symbol = peek();
				if (symbol.is("position")) {
					parse("position to COORD_EXPR EOL");
					//set OBJECT position to COORD_EXPR
					sys(SET_POSITION);
					return replace(start, "STATEMENT");
				} else if (symbol.is("disciple")) {
					parse("disciple CONST_EXPR [with sound] EOL");
					//set OBJECT disciple CONST_EXPR [with sound]
					sys(SET_DISCIPLE);
					return replace(start, "STATEMENT");
				} else if (symbol.is("focus")) {
					accept("focus");
					symbol = peek();
					if (symbol.is("to")) {
						parse("to COORD_EXPR EOL");
						//set OBJECT focus to COORD_EXPR
						sys(SET_FOCUS);
						return replace(start, "STATEMENT");
					} else if (symbol.is("on")) {
						parse("on OBJECT EOL");
						//set OBJECT focus on OBJECT
						sys(SET_FOCUS_ON_OBJECT);
						return replace(start, "STATEMENT");
					} else {
						throw new ParseException("Unexpected token: "+symbol+". Expected: to|on", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("anim")) {
					parse("anim CONST_EXPR EOL");
					//set OBJECT anim CONST_EXPR
					sys(OVERRIDE_STATE_ANIMATION);
					return replace(start, "STATEMENT");
				} else if (symbol.is("properties")) {
					accept("properties");
					symbol = peek();
					if (symbol.is("inner")) {
						//set OBJECT properties inner EXPRESSION outer EXPRESSION [calm EXPRESSION]
						parse("inner EXPRESSION outer EXPRESSION [calm EXPRESSION] EOL");
						sys(CHANGE_INNER_OUTER_PROPERTIES);
						return replace(start, "STATEMENT");
					} else if (symbol.is("town")) {
						parse("town OBJECT flock position COORD_EXPR distance EXPRESSION radius EXPRESSION flock OBJECT EOL");
						//set OBJECT properties town OBJECT flock position COORD_EXPR distance EXPRESSION radius EXPRESSION flock OBJECT
						sys(VORTEX_PARAMETERS);
						return replace(start, "STATEMENT");
					} else if (symbol.is("degrees")) {
						parse("degrees EXPRESSION rainfall EXPRESSION snowfall EXPRESSION overcast EXPRESSION fallspeed EXPRESSION EOL");
						//set OBJECT properties degrees EXPRESSION rainfall EXPRESSION snowfall EXPRESSION overcast EXPRESSION fallspeed EXPRESSION
						sys(CHANGE_WEATHER_PROPERTIES);
						return replace(start, "STATEMENT");
					} else if (symbol.is("time")) {
						parse("time EXPRESSION fade EXPRESSION EOL");
						//set OBJECT properties time EXPRESSION fade EXPRESSION
						sys(CHANGE_TIME_FADE_PROPERTIES);
						return replace(start, "STATEMENT");
					} else if (symbol.is("clouds")) {
						parse("clouds EXPRESSION shade EXPRESSION height EXPRESSION EOL");
						//set OBJECT properties clouds EXPRESSION shade EXPRESSION height EXPRESSION
						sys(CHANGE_CLOUD_PROPERTIES);
						return replace(start, "STATEMENT");
					} else if (symbol.is("sheetmin")) {
						parse("sheetmin EXPRESSION sheetmax EXPRESSION forkmin EXPRESSION forkmax EXPRESSION EOL");
						//set OBJECT properties sheetmin EXPRESSION sheetmax EXPRESSION forkmin EXPRESSION forkmax EXPRESSION
						sys(CHANGE_LIGHTNING_PROPERTIES);
						return replace(start, "STATEMENT");
					} else {
						throw new ParseException("Unexpected token: "+symbol+". Expected: inner|town|degrees|time|clouds|sheetmin", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("text")) {
					parse("text property text CONST_EXPR category CONST_EXPR EOL");
					//set OBJECT text property text CONST_EXPR category CONST_EXPR
					sys(HIGHLIGHT_PROPERTIES);
					return replace(start, "STATEMENT");
				} else if (symbol.is("velocity")) {
					//set OBJECT velocity heading COORD_EXPR speed EXPRESSION [angular x EXPRESSION y EXPRESSION z EXPRESSION]
					parse("velocity heading COORD_EXPR speed EXPRESSION [angular x EXPRESSION y EXPRESSION z EXPRESSION] EOL");
					sys(SET_HEADING_AND_SPEED);
					return replace(start, "STATEMENT");
				} else if (symbol.is("target")) {
					parse("target COORD_EXPR time EXPRESSION EOL");
					//set OBJECT target COORD_EXPR time EXPRESSION
					sys(SET_TARGET);
					return replace(start, "STATEMENT");
				} else if (symbol.is("time")) {
					parse("time to EXPRESSION second|seconds EOL");
					//set OBJECT time to EXPRESSION second|seconds
					sys(SET_TIMER_TIME);
					return replace(start, "STATEMENT");
				} else if (symbol.is("radius")) {
					parse("radius EXPRESSION EOL");
					//set OBJECT radius EXPRESSION
					sys(SET_MAGIC_RADIUS);
					return replace(start, "STATEMENT");
				} else if (symbol.is("mana")) {
					parse("mana EXPRESSION EOL");
					//set OBJECT mana EXPRESSION
					sys(GAME_SET_MANA);
					return replace(start, "STATEMENT");
				} else if (symbol.is("temperature")) {
					parse("temperature EXPRESSION EOL");
					//set OBJECT temperature EXPRESSION
					sys(SET_TEMPERATURE);
					return replace(start, "STATEMENT");
				} else if (symbol.is("forward") || symbol.is("reverse")) {
					//set OBJECT forward|reverse walk path CONST_EXPR from EXPRESSION to EXPRESSION
					symbol = parse("forward|reverse walk path IDENTIFIER")[3];
					String pathEnum = challengeName + symbol.token.value;
					int constVal = getConstant(pathEnum);
					pushi(constVal);
					parse("from EXPRESSION to EXPRESSION EOL");
					sys(WALK_PATH);
					return replace(start, "STATEMENT");
				} else if (symbol.is("desire")) {
					accept("desire");
					symbol = peek();
					if (symbol.is("maximum")) {
						parse("maximum CONST_EXPR to EXPRESSION EOL");
						//set OBJECT desire maximum CONST_EXPR to EXPRESSION
						sys(CREATURE_SET_DESIRE_MAXIMUM);
						return replace(start, "STATEMENT");
					} else if (symbol.is("boost")) {
						parse("boost CONST_EXPR EXPRESSION EOL");
						//set OBJECT desire boost TOWN_DESIRE_INFO EXPRESSION
						sys(SET_TOWN_DESIRE_BOOST);
						return replace(start, "STATEMENT");
					} else {
						parseConstExpr(true);
						symbol = peek();
						if (symbol.is("to")) {
							parse("to EXPRESSION EOL");
							//set OBJECT desire CONST_EXPR to EXPRESSION
							sys(CREATURE_SET_DESIRE_VALUE);
							return replace(start, "STATEMENT");
						} else {
							parse("CONST_EXPR EOL");
							//set OBJECT desire CONST_EXPR CONST_EXPR
							sys(CREATURE_SET_DESIRE_ACTIVATED3);
							return replace(start, "STATEMENT");
						}
					}
				} else if (symbol.is("only")) {
					parse("only desire CONST_EXPR EOL");
					//set OBJECT only desire CONST_EXPR
					pushf(86400);
					sys(SET_CREATURE_ONLY_DESIRE);
					return replace(start, "STATEMENT");
				} else if (symbol.is("disable")) {
					parse("disable only desire EOL");
					//set OBJECT disable only desire
					sys(SET_CREATURE_ONLY_DESIRE_OFF);
					return replace(start, "STATEMENT");
				} else if (symbol.is("magic")) {
					parse("magic properties CONST_EXPR [time EXPRESSION] EOL");
					//set OBJECT magic properties MAGIC_TYPE [time EXPRESSION]
					sys(SET_MAGIC_PROPERTIES);
					return replace(start, "STATEMENT");
				} else if (symbol.is("all")) {
					//set OBJECT all desires CONST_EXPR
					parse("all desires CONST_EXPR EOL");
					sys(CREATURE_SET_DESIRE_ACTIVATED);
					return replace(start, "STATEMENT");
				} else if (symbol.is("priority")) {
					parse("priority EXPRESSION EOL");
					//set OBJECT priority EXPRESSION
					sys(CREATURE_SET_AGENDA_PRIORITY);
					return replace(start, "STATEMENT");
				} else if (symbol.is("home")) {
					parse("home position COORD_EXPR EOL");
					//set OBJECT home position COORD_EXPR
					sys(SET_CREATURE_HOME);
					return replace(start, "STATEMENT");
				} else if (symbol.is("creed")) {
					parse("creed properties hand CONST_EXPR scale EXPRESSION power EXPRESSION time EXPRESSION EOL");
					//set OBJECT creed properties hand CONST_EXPR scale EXPRESSION power EXPRESSION time EXPRESSION
					sys(SET_CREATURE_CREED_PROPERTIES);
					return replace(start, "STATEMENT");
				} else if (symbol.is("name")) {
					parse("name CONST_EXPR EOL");
					//set OBJECT name CONST_EXPR
					sys(SET_CREATURE_NAME);
					return replace(start, "STATEMENT");
				} else if (symbol.is("fade")) {
					accept("fade");
					symbol = peek();
					if (symbol.is("start")) {
						parse("start scale EXPRESSION end scale EXPRESSION start transparency EXPRESSION end transparency EXPRESSION time EXPRESSION EOL");
						//set OBJECT fade start scale EXPRESSION end scale EXPRESSION start transparency EXPRESSION end transparency EXPRESSION time EXPRESSION
						sys(SET_MIST_FADE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("in")) {
						parse("in time EXPRESSION EOL");
						//set OBJECT fade in time EXPRESSION
						sys(SET_OBJECT_FADE_IN);
						return replace(start, "STATEMENT");
					} else {
						throw new ParseException("Unexpected token: "+symbol+". Expected: start|in", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("belief")) {
					parse("belief scale EXPRESSION EOL");
					//set OBJECT belief scale EXPRESSION
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else if (symbol.is("player")) {
					parse("player EXPRESSION");
					symbol = peek();
					if (symbol.is("relative")) {
						parse("relative belief EXPRESSION EOL");
						//set OBJECT player EXPRESSION relative belief EXPRESSION
						sys(OBJECT_RELATIVE_BELIEF);
						return replace(start, "STATEMENT");
					} else if (symbol.is("belief")) {
						parse("belief EXPRESSION EOL");
						//set OBJECT player EXPRESSION belief EXPRESSION
						sys(SET_PLAYER_BELIEF);
						return replace(start, "STATEMENT");
					} else {
						throw new ParseException("Unexpected token: "+symbol+". Expected: relative|belief", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("building")) {
					parse("building properties CONST_EXPR size EXPRESSION [destroys when placed] EOL");
					//set OBJECT building properties ABODE_NUMBER size EXPRESSION [destroys when placed]
					sys(SET_SCAFFOLD_PROPERTIES);
					return replace(start, "STATEMENT");
				} else if (symbol.is("carrying")) {
					parse("carrying CONST_EXPR EOL");
					//set OBJECT carrying CARRIED_OBJECT
					sys(SET_OBJECT_CARRYING);
					return replace(start, "STATEMENT");
				} else if (symbol.is("music")) {
					parse("music position to COORD_EXPR EOL");
					//set OBJECT music position to COORD_EXPR
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else if (symbol.is("colour")) {
					parse("colour red EXPRESSION green EXPRESSION blue EXPRESSION EOL");
					//set OBJECT colour red EXPRESSION green EXPRESSION blue EXPRESSION
					sys(SET_OBJECT_COLOUR);
					return replace(start, "STATEMENT");
				} else if (symbol.is("score")) {
					parse("score EXPRESSION EOL");
					//set OBJECT score EXPRESSION
					sys(SET_OBJECT_SCORE);
					return replace(start, "STATEMENT");
				} else if (symbol.is("in")) {
					parse("in player EXPRESSION hand EOL");
					//set OBJECT in player EXPRESSION hand
					sys(SET_OBJECT_IN_PLAYER_HAND);
					return replace(start, "STATEMENT");
				} else if (symbol.is("master")) {
					parse("master OBJECT EOL");
					//set OBJECT master OBJECT
					sys(SET_CREATURE_MASTER);
					return replace(start, "STATEMENT");
				} else if (symbol.is("distance")) {
					parse("distance from home EXPRESSION EOL");
					//set OBJECT distance from home EXPRESSION
					sys(SET_CREATURE_DISTANCE_FROM_HOME);
					return replace(start, "STATEMENT");
				} else if (symbol.is("dolphin")) {
					parse("dolphin move from COORD_EXPR to COORD_EXPR EOL");
					//set OBJECT dolphin move from COORD_EXPR to COORD_EXPR
					sys(SET_DOLPHIN_MOVE);
					return replace(start, "STATEMENT");
				} else {
					parse("CONST_EXPR development EOL");
					//set OBJECT CONST_EXPR development
					sys(SET_CREATURE_DEV_STAGE);
					return replace(start, "STATEMENT");
				}
			} else {
				symbol = parseExpression(true);
				if (symbol != null) {
					parse("land balance EXPRESSION EOL");
					//set EXPRESSION land balance EXPRESSION
					sys(SET_LAND_BALANCE);
					return replace(start, "STATEMENT");
				} else {
					symbol = peek();
					throw new ParseException("Expected: EXPRESSION|OBJECT", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			}
		}
	}
	
	private SymbolInstance parseDelete() throws ParseException {
		final int start = it.nextIndex();
		accept("delete");
		SymbolInstance symbol = peek(false);
		if (symbol.is("all")) {
			//delete all weather at COORD_EXPR radius EXPRESSION
			parse("all weather at COORD_EXPR radius EXPRESSION EOL");
			sys(KILL_STORMS_IN_AREA);
			return replace(start, "STATEMENT");
		} else if (symbol.is("fire")) {
			//delete delete fire at COORD_EXPR radius EXPRESSION
			parse("fire at COORD_EXPR radius EXPRESSION EOL");
			sys(GAME_DELETE_FIRE);
			return replace(start, "STATEMENT");
		} else if (symbol.is("fragments")) {
			//delete fragments at COORD_EXPR radius EXPRESSION
			parse("fragments at COORD_EXPR radius EXPRESSION EOL");
			sys(DELETE_FRAGMENTS_IN_RADIUS);
			return replace(start, "STATEMENT");
		} else {
			//delete OBJECT [with fade|with explosion|with temple explosion]
			parse("VARIABLE");
			symbol = peek();
			if (symbol.is("with")) {
				accept("with");
				symbol = peek();
				if (symbol.is("fade")) {
					accept("fade");
					pushi(1);
				} else if (symbol.is("explosion") || symbol.is("explode")) {
					next();
					pushi(2);
				} else if (symbol.is("temple")) {
					accept("temple");
					symbol = next();
					if (!symbol.is("explosion") && !symbol.is("explode")) {
						throw new ParseException("Unexpected token: "+symbol+". Expected: explosion|explode", lastParseException, file, symbol.token.line, symbol.token.col);
					}
					pushi(3);
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: fade|explosion|temple", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else {
				pushi(0);
			}
			accept(TokenType.EOL);
			sys(OBJECT_DELETE);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseRelease() throws ParseException {
		final int start = it.nextIndex();
		accept("release");
		SymbolInstance symbol = peek();
		if (symbol.is("computer")) {
			parse("computer player EXPRESSION EOL");
			//release computer player EXPRESSION
			sys(RELEASE_COMPUTER_PLAYER);
			return replace(start, "STATEMENT");
		} else {
			parseObject(true);
			symbol = peek();
			if (symbol.is("focus")) {
				parse("focus EOL");
				//release OBJECT focus
				sys(RELEASE_OBJECT_FOCUS);
				return replace(start, "STATEMENT");
			} else {
				accept(TokenType.EOL);
				//release OBJECT
				sys(RELEASE_FROM_SCRIPT);
				return replace(start, "STATEMENT");
			}
		}
	}
	
	private SymbolInstance parseEnableDisable() throws ParseException {
		final int start = it.nextIndex();
		if (checkAhead("ANY countdown")) {
			SymbolInstance action = next();
			accept("countdown");
			if (action.is("enable")) {
				//enable countdown timer up|down with EXPRESSION seconds
				parse("timer up|down with EXPRESSION seconds EOL");
				sys(START_COUNTDOWN_TIMER);
				return replace(start, "STATEMENT");
			} else {
				parse("timer EOL");
				//disable countdown timer
				sys(END_COUNTDOWN_TIMER);
				return replace(start, "STATEMENT");
			}
		} else {
			SymbolInstance action = parse("enable|disable")[0];
			SymbolInstance symbol = peek();
			if (symbol.is("leash")) {
				accept("leash");
				symbol = peek();
				if (symbol.is("on")) {
					parse("on OBJECT EOL");
					//enable|disable leash on OBJECT
					sys(SET_LEASH_WORKS);
					return replace(start, "STATEMENT");
				} else if (symbol.is("draw")) {
					parse("draw EOL");
					//enable|disable leash draw
					sys(SET_DRAW_LEASH);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: on|draw", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("player")) {
				parse("player EXPRESSION");
				symbol = peek();
				if (symbol.is("wind")) {
					parse("wind resistance EOL");
					//enable|disable player EXPRESSION wind resistance
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else if (symbol.is("virtual")) {
					parse("virtual influence EOL");
					//enable|disable player EXPRESSION virtual influence
					sys(SET_VIRTUAL_INFLUENCE);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: wind|virtual", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("fight")) {
				accept("fight");
				symbol = peek();
				if (symbol.is("exit")) {
					parse("exit EOL");
					//enable|disable fight exit
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else if (symbol.is("camera")) {
					parse("camera exit EOL");
					//enable|disable fight camera exit
					sys(SET_FIGHT_CAMERA_EXIT);
					return replace(start, "STATEMENT");
				} else if (symbol.is("lock")) {
					parse("lock EOL");
					//enable|disable fight lock
					sys(SET_FIGHT_LOCK);
					return replace(start, "STATEMENT");
				} else if (symbol.is("queue")) {
					parse("queue only EOL");
					//enable|disable fight queue only
					sys(SET_FIGHT_QUEUE_ONLY);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: exit|camera|lock|queue", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("computer")) {
				parse("computer player EXPRESSION EOL");
				//enable|disable computer player EXPRESSION
				sys(ENABLE_DISABLE_COMPUTER_PLAYER1);
				return replace(start, "STATEMENT");
			} else if (symbol.is("game")) {
				parse("game time EOL");
				//enable|disable game time
				sys(GAME_TIME_ON_OFF);
				return replace(start, "STATEMENT");
			} else if (symbol.is("help")) {
				parse("help system EOL");
				//enable|disable help system
				sys(SET_HELP_SYSTEM);
				return replace(start, "STATEMENT");
			} else if (symbol.is("creature")) {
				accept("creature");
				symbol = peek();
				if (symbol.is("sound")) {
					parse("sound EOL");
					//enable|disable creature sound
					sys(SET_CREATURE_SOUND);
					return replace(start, "STATEMENT");
				} else if (symbol.is("in")) {
					parse("in temple EOL");
					//enable|disable creature in temple
					sys(SET_CREATURE_IN_TEMPLE);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: sound|in", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("sound")) {
				parse("sound effects EOL");
				//enable|disable sound effects
				sys(SET_GAME_SOUND);
				return replace(start, "STATEMENT");
			} else if (symbol.is("spell")) {
				parse("spell CONST_EXPR");
				symbol = peek();
				if (symbol.is("in")) {
					//enable|disable spell CONST_EXPR in OBJECT
					parse("in OBJECT EOL");
					sys(SET_MAGIC_IN_OBJECT);
					return replace(start, "STATEMENT");
				} else if (symbol.is("for")) {
					//enable|disable spell CONST_EXPR for player EXPRESSION
					parse("for player EXPRESSION EOL");
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: in|for", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("angle")) {
				parse("angle sound EOL");
				//enable|disable angle sound
				sys(START_ANGLE_SOUND1);
				return replace(start, "STATEMENT");
			} else if (symbol.is("pitch")) {
				parse("pitch sound EOL");
				//enable|disable pitch sound
				sys(START_ANGLE_SOUND2);
				return replace(start, "STATEMENT");
			} else if (symbol.is("highlight")) {
				parse("highlight draw EOL");
				//enable|disable highlight draw
				sys(SET_DRAW_HIGHLIGHT);
				return replace(start, "STATEMENT");
			} else if (symbol.is("intro")) {
				parse("intro building EOL");
				//enable|disable intro building
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("temple")) {
				parse("temple EOL");
				//enable|disable temple
				sys(SET_INTERFACE_CITADEL);
				return replace(start, "STATEMENT");
			} else if (symbol.is("climate")) {
				accept("climate");
				symbol = peek();
				if (symbol.is("weather")) {
					parse("weather EOL");
					//enable|disable climate weather
					sys(PAUSE_UNPAUSE_CLIMATE_SYSTEM);
					return replace(start, "STATEMENT");
				} else if (symbol.is("create")) {
					parse("create storms EOL");
					//enable|disable climate create storms
					sys(PAUSE_UNPAUSE_STORM_CREATION_IN_CLIMATE_SYSTEM);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: weather|create", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("music")) {
				parse("music on OBJECT EOL");
				//enable|disable music on OBJECT
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("alignment")) {
				parse("alignment music EOL");
				//enable|disable alignment music
				sys(ENABLE_DISABLE_ALIGNMENT_MUSIC);
				return replace(start, "STATEMENT");
			} else if (symbol.is("clipping")) {
				parse("clipping distance [EXPRESSION] EOL", 0f);
				//enable|disable clipping distance [EXPRESSION]
				sys(SET_GRAPHICS_CLIPPING);
				return replace(start, "STATEMENT");
			} else if (symbol.is("camera")) {
				parse("camera fixed rotation at COORD_EXPR EOL");
				//enable|disable camera fixed rotation at COORD_EXPR
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("jc")) {
				parse("jc special CONST_EXPR on OBJECT [with number EXPRESSION] EOL");
				//enable|disable jc special CONST_EXPR on OBJECT [with number EXPRESSION]
				sys(THING_JC_SPECIAL);
				return replace(start, "STATEMENT");
			} else if (symbol.is("villager")) {
				parse("villager sound EOL");
				//enable|disable villager sound
				sys(SET_VILLAGER_SOUND);
				return replace(start, "STATEMENT");
			} else if (symbol.is("right")) {
				parse("right hand only for OBJECT EOL");
				//enable|disable right hand only for OBJECT
				sys(CREATURE_SET_RIGHT_HAND_ONLY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("scoreboard")) {
				parse("scoreboard draw EOL");
				//enable|disable scoreboard draw
				sys(SET_DRAW_SCOREBOARD);
				return replace(start, "STATEMENT");
			} else if (symbol.is("bookmark")) {
				parse("bookmark on OBJECT EOL");
				//enable|disable bookmark on OBJECT EOL
				sys(SET_BOOKMARK_ON_OBJECT);
				return replace(start, "STATEMENT");
			} else {
				symbol = parseObject(false);
				if (symbol != null) {
					symbol = peek();
					if (symbol.is("active")) {
						parse("active EOL");
						//enable|disable OBJECT active
						sys(SET_ACTIVE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("attack")) {
						parse("attack own town EOL");
						//enable|disable OBJECT attack own town
						throw new ParseException("Statement not implemented", file, line, col);
						//return replace(start, "STATEMENT");
					} else if (symbol.is("reaction")) {
						parse("reaction EOL");
						//enable|disable OBJECT reaction
						throw new ParseException("Statement not implemented", file, line, col);
						//return replace(start, "STATEMENT");
					} else if (symbol.is("development")) {
						parse("development script EOL");
						//enable|disable OBJECT development script
						sys(CREATURE_IN_DEV_SCRIPT);
						return replace(start, "STATEMENT");
					} else if (symbol.is("spell")) {
						parse("spell reversion EOL");
						//enable|disable OBJECT spell reversion
						throw new ParseException("Statement not implemented", file, line, col);
						//return replace(start, "STATEMENT");
					} else if (symbol.is("anim")) {
						parse("anim time modify EOL");
						//enable|disable OBJECT anim time modify
						sys(SET_ANIMATION_MODIFY);
						return replace(start, "STATEMENT");
					} else if (symbol.is("friends")) {
						parse("friends with OBJECT EOL");
						//enable|disable OBJECT friends with OBJECT
						sys(CREATURE_FORCE_FRIENDS);
						return replace(start, "STATEMENT");
					} else if (symbol.is("auto")) {
						accept("auto");
						symbol = peek();
						if (symbol.is("fighting")) {
							parse("fighting EOL");
							//enable|disable OBJECT auto fighting
							sys(SET_CREATURE_AUTO_FIGHTING);
							return replace(start, "STATEMENT");
						} else if (symbol.is("scale")) {
							accept("scale");
							if (action.is("enable")) {
								//enable OBJECT auto scale EXPRESSION
								parseExpression(true);
							} else {
								//disable OBJECT auto scale
								pushf(0);
							}
							accept(TokenType.EOL);
							sys(CREATURE_AUTOSCALE);
							return replace(start, "STATEMENT");
						} else {
							throw new ParseException("Unexpected token: "+symbol+". Expected: fighting|scale", lastParseException, file, symbol.token.line, symbol.token.col);
						}
					} else if (symbol.is("only")) {
						parse("only for scripts EOL");
						//enable|disable OBJECT only for scripts
						throw new ParseException("Statement not implemented", file, line, col);
						//return replace(start, "STATEMENT");
					} else if (symbol.is("poisoned")) {
						parse("poisoned EOL");
						//enable|disable OBJECT poisoned
						sys(SET_POISONED);
						return replace(start, "STATEMENT");
					} else if (symbol.is("build")) {
						parse("build worship site EOL");
						//enable|disable OBJECT build worship site
						sys(SET_CAN_BUILD_WORSHIPSITE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("skeleton")) {
						parse("skeleton EOL");
						//enable|disable OBJECT skeleton
						sys(SET_SKELETON);
						return replace(start, "STATEMENT");
					} else if (symbol.is("indestructible")) {
						parse("indestructible EOL");
						//enable|disable OBJECT indestructible
						sys(SET_INDESTRUCTABLE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("hurt")) {
						parse("hurt by fire EOL");
						//enable|disable OBJECT hurt by fire
						sys(SET_HURT_BY_FIRE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("set")) {
						parse("set on fire EOL");
						//enable|disable OBJECT set on fire
						sys(SET_SET_ON_FIRE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("on")) {
						parse("on fire EXPRESSION EOL");
						//enable|disable OBJECT on fire EXPRESSION
						sys(SET_ON_FIRE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("moveable")) {
						parse("moveable EOL");
						//enable|disable OBJECT moveable
						sys(SET_ID_MOVEABLE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("pickup")) {
						parse("pickup EOL");
						//enable|disable OBJECT pickup
						sys(SET_ID_PICKUPABLE);
						return replace(start, "STATEMENT");
					} else if (symbol.is("high")) {
						parse("high graphics|gfx detail EOL");
						//enable|disable OBJECT high graphics|gfx detail
						sys(SET_HIGH_GRAPHICS_DETAIL);
						return replace(start, "STATEMENT");
					} else if (symbol.is("affected")) {
						parse("affected by wind EOL");
						//enable|disable OBJECT affected by wind
						sys(SET_AFFECTED_BY_WIND);
						return replace(start, "STATEMENT");
					} else if (symbol.is("can")) {
						accept("can");
						symbol = peek();
						if (symbol.is("be")) {
							parse("be leashed to EOL");
							//enable|disable OBJECT can be leashed to
							sys(CAN_BE_LEASHED);
							return replace(start, "STATEMENT");
						} else if (symbol.is("drop")) {
							parse("drop EOL");
							//enable|disable OBJECT can drop
							sys(SET_CREATURE_CAN_DROP);
							return replace(start, "STATEMENT");
						} else if (symbol.is("learn")) {
							parse("learn CONST_EXPR EOL");
							//enable|disable OBJECT can learn CREATURE_ACTION_LEARNING_TYPE EOL
							sys(CREATURE_CAN_LEARN);
							return replace(start, "STATEMENT");
						} else {
							throw new ParseException("Unexpected token: "+symbol+". Expected: be|drop|learn", file, symbol.token.line, symbol.token.col);
						}
					} else if (symbol.is("tattoo")) {
						parse("tattoo CONST_EXPR EOL");
						//enable|disable OBJECT tattoo CONST_EXPR
						sys(SET_OBJECT_TATTOO);
						return replace(start, "STATEMENT");
					} else if (symbol.is("immune")) {
						parse("immune to spells EOL");
						//enable|disable OBJECT immune to spells
						sys(ENABLE_OBJECT_IMMUNE_TO_SPELLS);
						return replace(start, "STATEMENT");
					} else if (symbol.is("follow")) {
						parse("follow master EOL");
						//enable|disable OBJECT follow master
						sys(SET_CREATURE_FOLLOW_MASTER);
						return replace(start, "STATEMENT");
					} else if (symbol.is("navigation")) {
						parse("navigation EOL");
						//enable|disable OBJECT navigation
						sys(SET_OBJECT_NAVIGATION);
						return replace(start, "STATEMENT");
					} else if (symbol.is("dice")) {
						parse("dice check EOL");
						//enable|disable OBJECT dice check
						sys(SET_DIE_ROLL_CHECK);
						return replace(start, "STATEMENT");
					} else {
						throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
					}
				} else {
					symbol = parseConstExpr(false);
					if (symbol != null) {
						parse("avi sequence EOL");
						//enable|disable CONST_EXPR avi sequence
						sys(SET_AVI_SEQUENCE);
						return replace(start, "STATEMENT");
					} else {
						symbol = peek();
						throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
					}
				}
			}
		}
	}
	
	private SymbolInstance parseOpenClose() throws ParseException {
		final int start = it.nextIndex();
		if (checkAhead("close dialogue")) {
			parse("close dialogue EOL");
			//close dialogue
			sys(GAME_CLOSE_DIALOGUE);
			return replace(start, "STATEMENT");
		} else {
			parse("open|close OBJECT EOL");
			//open|close OBJECT
			sys(SET_OPEN_CLOSE);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseTeach() throws ParseException {
		final int start = it.nextIndex();
		parse("teach OBJECT");
		SymbolInstance symbol = peek();
		if (symbol.is("all")) {
			accept("all");
			symbol = peek();
			if (symbol.is("excluding")) {
				parse("excluding CONST_EXPR EOL");
				//teach OBJECT all excluding CONST_EXPR
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else {
				//teach OBJECT all
				accept(TokenType.EOL);
				sys(CREATURE_LEARN_EVERYTHING);
				return replace(start, "STATEMENT");
			}
		} else if (symbol.is("everything")) {
			//teach OBJECT everything
			parse("everything EOL");
			sys(CREATURE_LEARN_EVERYTHING);
			return replace(start, "STATEMENT");
		} else {
			parse("CONST_EXPR CONST_EXPR CONST_EXPR [percentage EXPRESSION] EOL", 1f);
			//teach OBJECT CREATURE_ACTION_LEARNING_TYPE CREATURE_ACTION_SUBTYPE SCRIPT_BOOL [percentage EXPRESSION]
			sys(CREATURE_SET_KNOWS_ACTION);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseForce() throws ParseException {
		final int start = it.nextIndex();
		accept("force");
		SymbolInstance symbol = peek();
		if (symbol.is("action")) {
			parse("action OBJECT finish EOL");
			//force action OBJECT finish
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else if (symbol.is("computer")) {
			//force computer player EXPRESSION action STRING OBJECT [OBJECT]
			parse("computer player EXPRESSION action STRING OBJECT");
			symbol = peek(false);
			if (symbol.is(TokenType.EOL)) {
				pusho(0);
			} else {
				parseObject(true);
			}
			accept(TokenType.EOL);
			sys(FORCE_COMPUTER_PLAYER_ACTION);
			return replace(start, "STATEMENT");
		} else {
			parse("OBJECT CONST_EXPR OBJECT");
			symbol = peek();
			if (symbol.is("at")) {
				parse("at COORD_EXPR EOL");
				//force OBJECT CONST_EXPR OBJECT at COORD_EXPR
				sys(DO_ACTION_AT_POS);
				return replace(start, "STATEMENT");
			} else {
				parse("[with OBJECT] EOL");
				//force OBJECT CONST_EXPR OBJECT [with OBJECT]
				sys(CREATURE_DO_ACTION);
				return replace(start, "STATEMENT");
			}
		}
	}
	
	private SymbolInstance parseInitialise() throws ParseException {
		//final int start = it.nextIndex();
		parse("initialise number of constant for OBJECT EOL");
		//initialise number of constant for OBJECT
		throw new ParseException("Statement not implemented", file, line, col);
		//return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseClear() throws ParseException {
		final int start = it.nextIndex();
		accept("clear");
		SymbolInstance symbol = peek();
		if (symbol.is("dropped")) {
			parse("dropped by OBJECT EOL");
			//clear dropped by OBJECT
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else if (symbol.is("computer")) {
			parse("computer player EXPRESSION actions EOL");
			//clear computer player EXPRESSION actions
			sys(GAME_CLEAR_COMPUTER_PLAYER_ACTIONS);
			return replace(start, "STATEMENT");
		} else if (symbol.is("clicked")) {
			accept("clicked");
			symbol = peek();
			if (symbol.is("object")) {
				parse("object EOL");
				//clear clicked object
				sys(CLEAR_CLICKED_OBJECT);
				return replace(start, "STATEMENT");
			} else if (symbol.is("position")) {
				parse("position EOL");
				//clear clicked position
				sys(CLEAR_CLICKED_POSITION);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: object|position", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("hit")) {
			accept("hit");
			symbol = peek();
			if (symbol.is("land")) {
				parse("land object EOL");
				//clear hit land object
				sys(CLEAR_HIT_LAND_OBJECT);
				return replace(start, "STATEMENT");
			} else if (symbol.is("object")) {
				parse("object EOL");
				//clear hit object
				sys(CLEAR_HIT_OBJECT);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: land|object", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("player")) {
			parse("player EXPRESSION spell charging EOL");
			//clear player EXPRESSION spell charging
			sys(CLEAR_PLAYER_SPELL_CHARGING);
			return replace(start, "STATEMENT");
		} else if (symbol.is("dialogue")) {
			parse("dialogue EOL");
			//clear dialogue
			sys(GAME_CLEAR_DIALOGUE);
			return replace(start, "STATEMENT");
		} else if (symbol.is("clipping")) {
			parse("clipping window time EXPRESSION EOL");
			//clear clipping window time EXPRESSION
			sys(CLEAR_CLIPPING_WINDOW);
			return replace(start, "STATEMENT");
		} else if (symbol.is("spells")) {
			parse("spells on OBJECT EOL");
			//clear spells on OBJECT
			sys(CLEAR_SPELLS_ON_OBJECT);
			return replace(start, "STATEMENT");
		} else {
			//clear OBJECT fight queue
			parse("OBJECT fight queue EOL");
			sys(CREATURE_CLEAR_FIGHT_QUEUE);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseAttach() throws ParseException {
		final int start = it.nextIndex();
		accept("attach");
		SymbolInstance symbol = peek();
		if (symbol.is("reaction")) {
			parse("reaction OBJECT CONST_EXPR EOL");
			//attach reaction OBJECT ENUM_REACTION
			sys(CREATE_REACTION);
			return replace(start, "STATEMENT");
		} else if (symbol.is("music")) {
			parse("music CONST_EXPR to OBJECT EOL");
			//attach music CONST_EXPR to OBJECT
			sys(ATTACH_MUSIC);
			return replace(start, "STATEMENT");
		} else if (symbol.is("3d") || symbol.is("sound")) {
			parse("[3d] sound tag CONST_EXPR");
			symbol = peek();
			if (symbol.is("to")) {
				pushi(DEFAULT_SOUNDBANK_NAME);
			} else {
				parseConstExpr(true);
			}
			parse("to OBJECT EOL");
			//attach [3d] sound tag CONST_EXPR [CONST_EXPR] to OBJECT
			sys(ATTACH_SOUND_TAG);
			return replace(start, "STATEMENT");
		} else {
			parseObject(true);
			symbol = peek();
			if (symbol.is("leash")) {
				parse("leash to");
				symbol = peek();
				if (symbol.is("hand")) {
					parse("hand EOL");
					//attach OBJECT leash to hand
					sys(ATTACH_OBJECT_LEASH_TO_HAND);
					return replace(start, "STATEMENT");
				} else {
					parse("OBJECT EOL");
					//attach OBJECT leash to OBJECT
					sys(ATTACH_OBJECT_LEASH_TO_OBJECT);
					return replace(start, "STATEMENT");
				}
			} else if (symbol.is("to")) {
				accept("to");
				symbol = peek();
				if (symbol.is("game")) {
					parse("game OBJECT for PLAYING_SIDE team EOL");
					//attach OBJECT to game OBJECT for PLAYING_SIDE team
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "STATEMENT");
				} else {
					parse("OBJECT [as leader] EOL");
					//attach OBJECT to OBJECT [as leader]
					sys(FLOCK_ATTACH);
					popo();	//maybe it returns the previous leader?
					return replace(start, "STATEMENT");
				}
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: leash|to", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		}
	}
	
	private SymbolInstance parseToggle() throws ParseException {
		final int start = it.nextIndex();
		parse("toggle player EXPRESSION leash EOL");
		//toggle player EXPRESSION leash
		sys(TOGGLE_LEASH);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseDetach() throws ParseException {
		final int start = it.nextIndex();
		accept("detach");
		SymbolInstance symbol = peek();
		if (symbol.is("player")) {
			parse("player from OBJECT from PLAYING_SIDE team EOL");
			//detach player from OBJECT from PLAYING_SIDE team
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else if (symbol.is("sound")) {
			parse("sound tag CONST_EXPR");
			symbol = peek();
			if (symbol.is("from")) {
				pushi(DEFAULT_SOUNDBANK_NAME);
			} else {
				parseConstExpr(true);
			}
			parse("from OBJECT EOL");
			//detach sound tag CONST_EXPR [CONST_EXPR] from OBJECT
			sys(DETACH_SOUND_TAG);
			return replace(start, "STATEMENT");
		} else if (symbol.is("reaction")) {
			parse("reaction OBJECT");
			symbol = peek(false);
			if (symbol.is(TokenType.EOL)) {
				accept(TokenType.EOL);
				//detach reaction OBJECT
				sys(REMOVE_REACTION);
				return replace(start, "STATEMENT");
			} else {
				parse("CONST_EXPR EOL");
				//detach reaction OBJECT CONST_EXPR
				sys(REMOVE_REACTION_OF_TYPE);
				return replace(start, "STATEMENT");
			}
		} else if (symbol.is("music")) {
			parse("music from OBJECT EOL");
			//detach music from OBJECT
			sys(DETACH_MUSIC);
			return replace(start, "STATEMENT");
		} else if (symbol.is("from")) {
			pusho(0);	//default OBJECT (0=random)
			parse("from OBJECT EOL");
			//detach [OBJECT] from OBJECT
			sys(FLOCK_DETACH);
			popo();	//returns the removed object
			return replace(start, "STATEMENT");
		} else {
			parseObject(true);
			symbol = peek();
			if (symbol.is("leash")) {
				parse("leash EOL");
				//detach OBJECT leash
				sys(DETACH_OBJECT_LEASH);
				return replace(start, "STATEMENT");
			} else if (symbol.is("in")) {
				parse("in game OBJECT from PLAYING_SIDE team EOL");
				//detach OBJECT in game OBJECT from PLAYING_SIDE team
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("from")) {
				parse("from OBJECT EOL");
				//detach [OBJECT] from OBJECT
				sys(FLOCK_DETACH);
				popo();	//returns the removed object
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: leash|in|from", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		}
	}
	
	private SymbolInstance parseSwap() throws ParseException {
		final int start = it.nextIndex();
		parse("swap creature from OBJECT to OBJECT EOL");
		//swap creature from OBJECT to OBJECT
		sys(SWAP_CREATURE);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseQueue() throws ParseException {
		final int start = it.nextIndex();
		accept("queue");
		SymbolInstance symbol = peek();
		if (symbol.is("computer")) {
			//queue computer player EXPRESSION action STRING OBJECT [OBJECT]
			parse("computer player EXPRESSION action STRING OBJECT");
			symbol = peek(false);
			if (symbol.is(TokenType.EOL)) {
				pusho(0);
			} else {
				parseObject(true);
			}
			accept(TokenType.EOL);
			sys(QUEUE_COMPUTER_PLAYER_ACTION);
			return replace(start, "STATEMENT");
		} else {
			parse("OBJECT fight");
			symbol = peek();
			if (symbol.is("move")) {
				parse("move CONST_EXPR [strength EXPRESSION] EOL");
				//queue OBJECT fight move FIGHT_MOVE [strength EXPRESSION]
				sys(SET_CREATURE_QUEUE_FIGHT_MOVE);
				return replace(start, "STATEMENT");
			} else if (symbol.is("step")) {
				parse("step CONST_EXPR EOL");
				//queue OBJECT fight step CONST_EXPR
				sys(SET_CREATURE_QUEUE_FIGHT_STEP);
				return replace(start, "STATEMENT");
			} else if (symbol.is("spell")) {
				parse("spell CONST_EXPR EOL");
				//queue OBJECT fight spell CONST_EXPR
				sys(SET_CREATURE_QUEUE_FIGHT_SPELL);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: move|step|spell", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		}
	}
	
	private SymbolInstance parsePauseUnpause() throws ParseException {
		final int start = it.nextIndex();
		parse("pause|unpause computer player EXPRESSION EOL");
		//pause|unpause computer player EXPRESSION
		sys(ENABLE_DISABLE_COMPUTER_PLAYER2);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseLoad() throws ParseException {
		final int start = it.nextIndex();
		accept("load");
		SymbolInstance symbol = peek();
		if (symbol.is("computer")) {
			parse("computer player EXPRESSION personality STRING EOL");
			//load computer player EXPRESSION personality STRING
			sys(LOAD_COMPUTER_PLAYER_PERSONALITY);
			return replace(start, "STATEMENT");
		} else if (symbol.is("map")) {
			parse("map STRING EOL");
			//load map STRING
			sys(LOAD_MAP);
			return replace(start, "STATEMENT");
		} else if (symbol.is("my_creature")) {
			parse("my_creature at COORD_EXPR EOL");
			//load my_creature at COORD_EXPR
			sys(LOAD_MY_CREATURE);
			return replace(start, "STATEMENT");
		} else if (symbol.is("creature")) {
			parse("creature CONST_EXPR STRING player EXPRESSION at COORD_EXPR EOL");
			//load creature CONST_EXPR STRING player EXPRESSION at COORD_EXPR
			sys(LOAD_CREATURE);
			return replace(start, "STATEMENT");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: computer|map|my_creature|creature", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseSave() throws ParseException {
		final int start = it.nextIndex();
		accept("save");
		SymbolInstance symbol = peek();
		if (symbol.is("computer")) {
			parse("computer player EXPRESSION personality STRING EOL");
			//save computer player EXPRESSION personality STRING
			sys(SAVE_COMPUTER_PLAYER_PERSONALITY);
			return replace(start, "STATEMENT");
		} else if (symbol.is("game")) {
			parse("game in slot EXPRESSION EOL");
			//save game in slot EXPRESSION
			sys(SAVE_GAME_IN_SLOT);
			return replace(start, "STATEMENT");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: computer|game", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseStop() throws ParseException {
		final int start = it.nextIndex();
		accept("stop");
		SymbolInstance symbol = peek();
		if (symbol.is("all")) {
			accept("all");
			symbol = peek();
			if (symbol.is("games")) {
				parse("games for OBJECT EOL");
				//stop all games for OBJECT
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("scripts")) {
				parse("scripts excluding");
				symbol = peek();
				if (symbol.is("files")) {
					parse("files STRING EOL");
					//stop all scripts excluding files STRING
					sys(STOP_ALL_SCRIPTS_IN_FILES_EXCLUDING);
					return replace(start, "STATEMENT");
				} else {
					parse("STRING EOL");
					//stop all scripts excluding STRING
					sys(STOP_ALL_SCRIPTS_EXCLUDING);
					return replace(start, "STATEMENT");
				}
			} else if (symbol.is("immersion")) {
				parse("immersion EOL");
				//stop all immersion
				sys(STOP_ALL_IMMERSION);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: games|scripts|immersion", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("script")) {
			parse("script STRING EOL");
			//stop script STRING
			sys(STOP_SCRIPT);
			return replace(start, "STATEMENT");
		} else if (symbol.is("scripts")) {
			parse("scripts in files STRING");
			symbol = peek();
			if (symbol.is("excluding")) {
				//stop scripts in file STRING excluding STRING
				parse("excluding STRING EOL");
				sys(STOP_SCRIPTS_IN_FILES_EXCLUDING);
				return replace(start, "STATEMENT");
			} else {
				//stop scripts in files STRING
				accept(TokenType.EOL);
				sys(STOP_SCRIPTS_IN_FILES);
				return replace(start, "STATEMENT");
			}
		} else if (symbol.is("sound")) {
			//stop sound CONST_EXPR [CONST_EXPR]
			pushb(false);
			parse("sound CONST_EXPR");
			symbol = peek(false);
			if (symbol.is(TokenType.EOL)) {
				pushi(DEFAULT_SOUNDBANK_NAME);
			} else {
				parseConstExpr(true);
			}
			sys(STOP_SOUND_EFFECT);
			return replace(start, "STATEMENT");
		} else if (symbol.is("immersion")) {
			parse("immersion CONST_EXPR EOL");
			//stop immersion IMMERSION_EFFECT_TYPE
			sys(STOP_IMMERSION);
			return replace(start, "STATEMENT");
		} else if (symbol.is("music")) {
			parse("music EOL");
			//stop music
			sys(STOP_MUSIC);
			return replace(start, "STATEMENT");
		} else if (symbol.is("dialogue")) {
			parse("dialogue sound EOL");
			//stop dialogue sound
			sys(STOP_DIALOGUE_SOUND);
			return replace(start, "STATEMENT");
		} else {
			parse("SPIRIT_TYPE spirit");
			symbol = peek();
			if (symbol.is("pointing")) {
				parse("pointing EOL");
				//stop SPIRIT_TYPE spirit pointing
				sys(STOP_POINTING);
				return replace(start, "STATEMENT");
			} else if (symbol.is("looking")) {
				parse("looking EOL");
				//stop SPIRIT_TYPE spirit looking
				sys(STOP_LOOKING);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: pointing|looking", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		}
	}
	
	private SymbolInstance parseStart() throws ParseException {
		final int start = it.nextIndex();
		accept("start");
		SymbolInstance symbol = peek();
		if (symbol.is("say")) {
			parse("say [extra] sound CONST_EXPR [at COORD_EXPR] EOL");
			//start say [extra] sound CONST_EXPR [at COORD_EXPR]
			sys(GAME_PLAY_SAY_SOUND_EFFECT);
			return replace(start, "STATEMENT");
		} else if (symbol.is("sound")) {
			parse("sound CONST_EXPR");
			symbol = peek(false);
			if (symbol.is(TokenType.EOL)) {
				pushi(DEFAULT_SOUNDBANK_NAME);
				pushc(0);
				pushc(0);
				pushc(0);
				pushb(false);
			} else if (symbol.is("at")) {
				pushi(DEFAULT_SOUNDBANK_NAME);
				parse("at COORD_EXPR");
				pushb(true);
			} else {
				symbol = parse("CONST_EXPR [at COORD_EXPR]")[1];
			}
			accept(TokenType.EOL);
			//start sound CONST_EXPR [CONST_EXPR] [at COORD_EXPR]
			sys(PLAY_SOUND_EFFECT);
			return replace(start, "STATEMENT");
		} else if (symbol.is("immersion")) {
			parse("immersion CONST_EXPR EOL");
			//start immersion IMMERSION_EFFECT_TYPE
			sys(START_IMMERSION);
			return replace(start, "STATEMENT");
		} else if (symbol.is("music")) {
			parse("music CONST_EXPR EOL");
			//start music CONST_EXPR
			pushf(0);
			sys(START_MUSIC);
			return replace(start, "STATEMENT");
		} else if (symbol.is("hand")) {
			parse("hand demo STRING [with pause on trigger] [without hand modify] EOL");
			//start hand demo STRING [with pause on trigger] [without hand modify]
			sys(PLAY_HAND_DEMO);
			return replace(start, "STATEMENT");
		} else if (symbol.is("jc")) {
			parse("jc special CONST_EXPR EOL");
			//start jc special CONST_EXPR
			sys(PLAY_JC_SPECIAL);
			return replace(start, "STATEMENT");
		} else {
			parseObject(true);
			symbol = peek();
			if (symbol.is("with")) {
				parse("with OBJECT as referee EOL");
				//start OBJECT with OBJECT as referee
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "STATEMENT");
			} else if (symbol.is("fade")) {
				parse("fade out EOL");
				//start OBJECT fade out
				sys(VORTEX_FADE_OUT);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: with|fade", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		}
	}
	
	private SymbolInstance parseDisband() throws ParseException {
		final int start = it.nextIndex();
		parse("disband OBJECT EOL");
		//disband OBJECT
		sys(FLOCK_DISBAND);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parsePopulate() throws ParseException {
		final int start = it.nextIndex();
		parse("populate OBJECT with EXPRESSION CONST_EXPR");
		SymbolInstance symbol = peek(false);
		if (symbol.is(TokenType.EOL)) {
			pushi(DEFAULT_SUBTYPE_NAME);
		} else {
			parseConstExpr(true);
		}
		accept(TokenType.EOL);
		//populate OBJECT with EXPRESSION CONST_EXPR [CONST_EXPR]
		sys(POPULATE_CONTAINER);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseAffect() throws ParseException {
		//final int start = it.nextIndex();
		parse("affect alignment by EXPRESSION EOL");
		//affect alignment by EXPRESSION
		throw new ParseException("Statement not implemented", file, line, col);
		//return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseSnapshot() throws ParseException {
		final int start = it.nextIndex();
		//snapshot CONST_EXPR quest|challenge [success EXPRESSION] [alignment EXPRESSION] CONST_EXPR SCRIPT[(PARAMETERS)]
		parse("snapshot CONST_EXPR quest|challenge");
		sys(GET_CAMERA_POSITION);
		sys(GET_CAMERA_FOCUS);
		SymbolInstance script = parse("[success EXPRESSION] [alignment EXPRESSION] CONST_EXPR IDENTIFIER")[5];
		String scriptName = script.token.value;
		int strptr = storeStringData(scriptName);
		objcode.getStringInstructions().add(getIp());
		pushi(strptr);
		int argc = 0;
		SymbolInstance symbol = peek();
		if (symbol.is("(")) {
			argc = parseParameters();
		}
		accept(TokenType.EOL);
		pushi(argc);
		sys(SNAPSHOT);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseUpdate() throws ParseException {
		final int start = it.nextIndex();
		parse("update snapshot");
		SymbolInstance symbol = peek();
		if (symbol.is("details")) {
			//TODO update snapshot details [success EXPRESSION] [alignment EXPRESSION] CONST_EXPR [taking picture]
			throw new ParseError("Statement not implemented", file, line, col);
			/*sys(GET_CAMERA_POSITION);
			sys(GET_CAMERA_FOCUS);
			parse("details [success EXPRESSION] [alignment EXPRESSION] CONST_EXPR [taking picture] EOL");
			pushChallengeId();
			sys(UPDATE_SNAPSHOT_PICTURE);
			return replace(start, "STATEMENT");*/
		} else {
			//update snapshot CONST_EXPR [success EXPRESSION] [alignment EXPRESSION] CONST_EXPR SCRIPT[(PARAMETERS)]
			SymbolInstance script = parse("CONST_EXPR [success EXPRESSION] [alignment EXPRESSION] CONST_EXPR IDENTIFIER")[6];
			String scriptName = script.token.value;
			int strptr = storeStringData(scriptName);
			objcode.getStringInstructions().add(getIp());
			pushi(strptr);
			int argc = 0;
			symbol = peek();
			if (symbol.is("(")) {
				argc = parseParameters();
			}
			accept(TokenType.EOL);
			pushi(argc);
			sys(UPDATE_SNAPSHOT);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseBuild() throws ParseException {
		final int start = it.nextIndex();
		parse("build building at COORD_EXPR desire EXPRESSION EOL");
		//build building at COORD_EXPR desire EXPRESSION
		sys(BUILD_BUILDING);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseRun() throws ParseException {
		final int start = it.nextIndex();
		accept("run");
		SymbolInstance symbol = peek();
		if (symbol.is("script")) {
			SymbolInstance script = parse("script IDENTIFIER")[1];
			String scriptName = script.token.value;
			symbol = peek();
			int argc = 0;
			if (symbol.is("(")) {
				argc = parseParameters();
			}
			accept(TokenType.EOL);
			//run script IDENTIFIER[(PARAMETERS)]
			call(scriptName, argc);
			return replace(start, "STATEMENT");
		} else if (symbol.is("map")) {
			parse("map script line STRING EOL");
			//run map script line STRING
			sys(MAP_SCRIPT_FUNCTION);
			return replace(start, "STATEMENT");
		} else if (symbol.is("background")) {
			SymbolInstance script = parse("background script IDENTIFIER")[2];
			String scriptName = script.token.value;
			symbol = peek();
			int argc = 0;
			if (symbol.is("(")) {
				argc = parseParameters();
			}
			accept(TokenType.EOL);
			//run background script IDENTIFIER[(PARAMETERS)]
			start(scriptName, argc);
			return replace(start, "STATEMENT");
		} else {
			parse("CONST_EXPR developer function EOL");
			//run CONST_EXPR developer function
			sys(DEV_FUNCTION);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseWait() throws ParseException {
		final int start = it.nextIndex();
		//wait until CONDITION
		int lblWait = getIp();
		accept("wait");
		SymbolInstance symbol = peek();
		if (symbol.is("until")) {
			next();	//skip
		}
		//parse("CONDITION EOL");
		parse("CONDITION");
		jz(lblWait);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseEnterExit() throws ParseException {
		final int start = it.nextIndex();
		parse("enter|exit temple EOL");
		//enter|exit temple
		sys(ENTER_EXIT_CITADEL);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseRestart() throws ParseException {
		final int start = it.nextIndex();
		accept("restart");
		SymbolInstance symbol = peek();
		if (symbol.is("music")) {
			parse("music on OBJECT EOL");
			//restart music on OBJECT
			throw new ParseException("Statement not implemented", file, line, col);
			//return replace(start, "STATEMENT");
		} else {
			parse("OBJECT EOL");
			//restart OBJECT
			sys(RESTART_OBJECT);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseState() throws ParseException {
		final int start = it.nextIndex();
		parse("state VARIABLE CONST_EXPR");
		SymbolInstance symbol = peek(false);
		if (symbol.is(TokenType.EOL)) {
			//state OBJECT CONST_EXPR
			accept(TokenType.EOL);
			sys(SET_SCRIPT_STATE);
		} else if (symbol.is("position")) {
			//state OBJECT CONST_EXPR position COORD_EXPR float EXPRESSION ulong EXPRESSION, EXPRESSION
			parse("position COORD_EXPR float EXPRESSION ulong EXPRESSION , EXPRESSION EOL");
			sys(SET_SCRIPT_STATE_WITH_PARAMS);
		}
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseMake() throws ParseException {
		final int start = it.nextIndex();
		accept("make");
		if (checkAhead("ANY spirit")) {
			parse("SPIRIT_TYPE spirit");
			SymbolInstance symbol = peek();
			if (symbol.is("point")) {
				accept("point");
				symbol = peek();
				if (symbol.is("to")) {
					parse("to OBJECT [in world] EOL");
					//make SPIRIT_TYPE spirit point to OBJECT [in world]
					sys(SPIRIT_POINT_GAME_THING);
					return replace(start, "STATEMENT");
				} else if (symbol.is("at")) {
					parse("at COORD_EXPR [in world] EOL");
					//make SPIRIT_TYPE spirit point at COORD_EXPR [in world]
					sys(SPIRIT_POINT_POS);
					return replace(start, "STATEMENT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: to|at", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("play")) {
				accept("play");
				symbol = peek();
				if (symbol.is("across")) {
					parse("across EXPRESSION down EXPRESSION CONST_EXPR [speed EXPRESSION] EOL", 1);
					//make SPIRIT_TYPE spirit play across EXPRESSION down EXPRESSION CONST_EXPR [speed EXPRESSION]
					sys(PLAY_SPIRIT_ANIM);
					return replace(start, "STATEMENT");
				} else {
					//make SPIRIT_TYPE spirit play CONST_EXPR at COORD_EXPR in world [speed EXPRESSION]
					parse("CONST_EXPR at COORD_EXPR in world [speed EXPRESSION] EOL", 1f);
					sys(PLAY_SPIRIT_ANIM_IN_WORLD);
					return replace(start, "STATEMENT");
				}
			} else if (symbol.is("cling")) {
				parse("cling across EXPRESSION down EXPRESSION EOL");
				//make SPIRIT_TYPE spirit cling across EXPRESSION down EXPRESSION
				sys(CLING_SPIRIT);
				return replace(start, "STATEMENT");
			} else if (symbol.is("fly")) {
				parse("fly across EXPRESSION down EXPRESSION EOL");
				//make SPIRIT_TYPE spirit fly across EXPRESSION down EXPRESSION
				sys(FLY_SPIRIT);
				return replace(start, "STATEMENT");
			} else if (symbol.is("look")) {
				parse("look at");
				symbol = parseObject(false);
				if (symbol != null) {
					accept(TokenType.EOL);
					//make SPIRIT_TYPE spirit look at OBJECT
					sys(LOOK_GAME_THING);
					return replace(start, "STATEMENT");
				} else {
					symbol = parseCoordExpr(false);
					if (symbol != null) {
						accept(TokenType.EOL);
						//make SPIRIT_TYPE spirit look at COORD_EXPR
						sys(LOOK_AT_POSITION);
						return replace(start, "STATEMENT");
					} else {
						symbol = peek();
						throw new ParseException("Expected: OBJECT|COORD_EXPR", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				}
			} else if (symbol.is("appear")) {
				parse("appear EOL");
				//make SPIRIT_TYPE spirit appear
				sys(SPIRIT_APPEAR);
				return replace(start, "STATEMENT");
			} else if (symbol.is("disappear")) {
				parse("disappear EOL");
				//make SPIRIT_TYPE spirit disappear
				sys(SPIRIT_DISAPPEAR);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: point|play|cling|fly|look|appear", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		} else {
			parse("OBJECT dance CONST_EXPR around COORD_EXPR time EXPRESSION EOL");
			//make OBJECT dance CONST_EXPR around COORD_EXPR time EXPRESSION
			sys(DANCE_CREATE);
			popo();	//returns an object representing the dance
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseEject() throws ParseException {
		final int start = it.nextIndex();
		parse("eject SPIRIT_TYPE spirit EOL");
		//eject SPIRIT_TYPE spirit
		sys(SPIRIT_EJECT);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseSend() throws ParseException {
		final int start = it.nextIndex();
		parse("send SPIRIT_TYPE spirit home EOL");
		//send SPIRIT_TYPE spirit home
		sys(SPIRIT_HOME);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseSay() throws ParseException {
		final int start = it.nextIndex();
		accept("say");
		SymbolInstance symbol = peek();
		if (symbol.is("sound")) {
			//say sound CONST_EXPR playing
			pushb(false);
			parse("sound CONST_EXPR playing EOL");
			sys(SAY_SOUND_EFFECT_PLAYING);
			return replace(start, "STATEMENT");
		} else {
			parse("[single line]");
			symbol = peek();
			if (symbol.is(TokenType.STRING)) {
				parseString();
				if (checkAhead("with number")) {
					//say [single line] STRING with number EXPRESSION [SAY_MODE] [by OBJECT]
					parse("with number EXPRESSION");
					symbol = peek();
					if (symbol.is("with")) {
						parse("with interaction");
						pushi(1);
					} else if (symbol.is("without")) {
						parse("without interaction");
						pushi(2);
					} else {
						pushi(0);
					}
					parse("[by OBJECT] EOL");
					sys(TEMP_TEXT_WITH_NUMBER);
					return replace(start, "STATEMENT");
				} else {
					//say [single line] STRING [SAY_MODE] [by OBJECT]
					symbol = peek(false);
					if (symbol.is("with")) {
						parse("with interaction");
						pushi(1);
					} else if (symbol.is("without")) {
						parse("without interaction");
						pushi(2);
					} else {
						pushi(0);
					}
					parse("[by OBJECT] EOL");
					sys(TEMP_TEXT);
					return replace(start, "STATEMENT");
				}
			} else {
				parseConstExpr(true);
				if (checkAhead("with number")) {
					//say [single line] CONST_EXPR with number EXPRESSION [SAY_MODE] [by OBJECT]
					parse("with number EXPRESSION");
					if (symbol.is("with")) {
						parse("with interaction");
						pushi(1);
					} else if (symbol.is("without")) {
						parse("without interaction");
						pushi(2);
					} else {
						pushi(0);
					}
					parse("[by OBJECT] EOL");
					sys(RUN_TEXT_WITH_NUMBER);
					return replace(start, "STATEMENT");
				} else {
					//say [single line] CONST_EXPR [SAY_MODE] [by OBJECT]
					symbol = peek(false);
					if (symbol.is("with")) {
						parse("with interaction");
						pushi(1);
					} else if (symbol.is("without")) {
						parse("without interaction");
						pushi(2);
					} else {
						pushi(0);
					}
					parse("[by OBJECT] EOL");
					sys(RUN_TEXT);
					return replace(start, "STATEMENT");
				}
			}
		}
	}
	
	private SymbolInstance parseDraw() throws ParseException {
		final int start = it.nextIndex();
		accept("draw");
		SymbolInstance symbol = peek();
		if (symbol.is("text")) {
			accept("text");
			symbol = peek();
			if (symbol.is(TokenType.STRING)) {
				parse("STRING across EXPRESSION down EXPRESSION width EXPRESSION height EXPRESSION size EXPRESSION fade in time EXPRESSION second|seconds EOL");
				//draw text STRING across EXPRESSION down EXPRESSION width EXPRESSION height EXPRESSION size EXPRESSION fade in time EXPRESSION second|seconds
				sys(GAME_DRAW_TEMP_TEXT);
				return replace(start, "STATEMENT");
			} else {
				parse("CONST_EXPR across EXPRESSION down EXPRESSION width EXPRESSION height EXPRESSION size EXPRESSION fade in time EXPRESSION second|seconds EOL");
				//draw text CONST_EXPR across EXPRESSION down EXPRESSION width EXPRESSION height EXPRESSION size EXPRESSION fade in time EXPRESSION second|seconds
				sys(GAME_DRAW_TEXT);
				return replace(start, "STATEMENT");
			}
		} else {
			parse("OBJECT lightbulb EXPRESSION EOL");
			//draw OBJECT lightbulb EXPRESSION EOL
			sys(SET_OBJECT_LIGHTBULB);
			return replace(start, "STATEMENT");
		}
	}
	
	private SymbolInstance parseFade() throws ParseException {
		final int start = it.nextIndex();
		accept("fade");
		SymbolInstance symbol = peek();
		if (symbol.is("all")) {
			parse("all draw text time EXPRESSION second|seconds EOL");
			//fade all draw text time EXPRESSION second|seconds
			sys(FADE_ALL_DRAW_TEXT);
			return replace(start, "STATEMENT");
		} else if (symbol.is("ready")) {
			parse("ready EOL");
			//fade ready
			sys(FADE_FINISHED);
			return replace(start, "STATEMENT");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: all|ready", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseStore() throws ParseException {
		final int start = it.nextIndex();
		parse("store camera details EOL");
		//store camera details
		sys(STORE_CAMERA_DETAILS);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseRestore() throws ParseException {
		final int start = it.nextIndex();
		parse("restore camera details EOL");
		//restore camera details
		sys(RESTORE_CAMERA_DETAILS);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseReset() throws ParseException {
		final int start = it.nextIndex();
		parse("reset camera lens EOL");
		//reset camera lens
		pushf(0);
		sys(SET_CAMERA_LENS);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseStatementCamera() throws ParseException {
		final int start = it.nextIndex();
		accept("camera");
		SymbolInstance symbol = peek();
		if (symbol.is("follow")) {
			parse("follow OBJECT distance EXPRESSION EOL");
			//camera follow OBJECT distance EXPRESSION
			sys(SET_FOCUS_AND_POSITION_FOLLOW);
			return replace(start, "STATEMENT");
		} else if (symbol.is("path")) {
			//camera path CONSTANT IDENTIFIER
			symbol = parse("path IDENTIFIER IDENTIFIER EOL")[1];
			String pathEnum = symbol.token.value;
			int constVal = getConstant(pathEnum);
			pushi(constVal);
			sys(RUN_CAMERA_PATH);
			return replace(start, "STATEMENT");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: follow|path", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseShake() throws ParseException {
		final int start = it.nextIndex();
		parse("shake camera at COORD_EXPR radius EXPRESSION amplitude EXPRESSION time EXPRESSION EOL");
		//shake camera at COORD_EXPR radius EXPRESSION amplitude EXPRESSION time EXPRESSION
		sys(SHAKE_CAMERA);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseFire() throws ParseException {
		final int start = it.nextIndex();
		parse("fire gun OBJECT EOL");
		//fire gun OBJECT
		sys(FIRE_GUN);
		return replace(start, "STATEMENT");
	}
	
	private SymbolInstance parseAssignment() throws ParseException {
		final int start = it.nextIndex();
		if (peek(1).is("of")) {
			parse("CONSTANT of VARIABLE");
			dup(1);
			dup(1);
			sys2(GET_PROPERTY);
			//
			SymbolInstance symbol = next();
			if (symbol.is("=")) {
				//CONSTANT of OBJECT = EXPRESSION
				popi();
				parse("EXPRESSION EOL");
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("+=")) {
				//CONSTANT of OBJECT += EXPRESSION
				parse("EXPRESSION EOL");
				addf();
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("-=")) {
				//CONSTANT of OBJECT -= EXPRESSION
				parse("EXPRESSION EOL");
				subf();
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("*=")) {
				//CONSTANT of OBJECT *= EXPRESSION
				parse("EXPRESSION EOL");
				mul();
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("/=")) {
				//CONSTANT of OBJECT /= EXPRESSION
				parse("EXPRESSION EOL");
				div();
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("++")) {
				//CONSTANT of OBJECT++
				accept(TokenType.EOL);
				pushf(1);
				addf();
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else if (symbol.is("--")) {
				//CONSTANT of OBJECT--
				accept(TokenType.EOL);
				pushf(1);
				subf();
				sys2(SET_PROPERTY);
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: =|+=|-=|*=|/=", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		} else {
			SymbolInstance symbol = accept(TokenType.IDENTIFIER);
			String varName = symbol.token.value;
			symbol = peek();
			if (symbol.is("[")) {
				//IDENTIFIER\[EXPRESSION\]
				accept("[");
				parseExpression(true);
				accept("]");
				pushvAddr(varName);
				ref_and_offset_push();
			} else {
				//IDENTIFIER
				if (implicitVarDecl && !localMap.containsKey(varName) && !globalMap.containsKey(varName)) {
					addLocalVar(varName);
				}
				pushf(0);
				pushvAddr(varName);
				ref_and_offset_push();
			}
			symbol = peek();
			if (symbol.is("=")) {
				//VARIABLE = EXPRESSION
				popi();
				accept("=");
				symbol = parseExpression(false);
				if (symbol == null) {
					symbol = parseObject(false);
					if (symbol == null) {
						symbol = peek();
						throw new ParseException("Expected: EXPRESSION|OBJECT", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				}
				accept(TokenType.EOL);
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else if (symbol.is("+=")) {
				//VARIABLE += EXPRESSION
				parse("+= EXPRESSION EOL");
				addf();
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else if (symbol.is("-=")) {
				//VARIABLE -= EXPRESSION
				parse("-= EXPRESSION EOL");
				subf();
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else if (symbol.is("*=")) {
				//VARIABLE *= EXPRESSION
				parse("*= EXPRESSION EOL");
				mul();
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else if (symbol.is("/=")) {
				//VARIABLE /= EXPRESSION
				parse("/= EXPRESSION EOL");
				div();
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else if (symbol.is("++")) {
				//VARIABLE++
				parse("++ EOL");
				pushf(1);
				addf();
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else if (symbol.is("--")) {
				//VARIABLE--
				parse("-- EOL");
				pushf(1);
				subf();
				ref_and_offset_pop();
				return replace(start, "STATEMENT");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: =|+=|-=|*=|/=|++|--", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		}
	}
	
	private SymbolInstance parseIf() throws ParseException {
		final int start = it.nextIndex();
		//IF_ELSIF_ELSE
		parse("if CONDITION EOL");
		Instruction jz_lblNextCond = jz();
		parseStatements();
		Instruction jump_lblEndBlock = jmp();
		SymbolInstance symbol = peek();
		while (symbol.is("elsif")) {
			jz_lblNextCond.intVal = getIp();
			parse("elsif CONDITION EOL");
			jz_lblNextCond = jz();
			parseStatements();
			int lblEndBlock = getIp();
			jump_lblEndBlock.intVal = lblEndBlock;
			jump_lblEndBlock = jmp();
			symbol = peek();
		}
		if (symbol.is("else")) {
			jz_lblNextCond.intVal = getIp();
			parse("else EOL");
			pushb(true);			//omg...
			jz_lblNextCond = jz();
			parseStatements();
			int lblEndBlock = getIp();
			jump_lblEndBlock.intVal = lblEndBlock;
			jump_lblEndBlock = jmp();
		}
		parse("end if EOL");
		int lblEndBlock = getIp();
		jz_lblNextCond.intVal = lblEndBlock;
		jump_lblEndBlock.intVal = lblEndBlock;
		return replace(start, "IF_ELSIF_ELSE");
	}
	
	private SymbolInstance parseWhile() throws ParseException {
		final int start = it.nextIndex();
		final boolean noYield = this.noYield;
		//WHILE
		Instruction except_lblExceptionHandler = except();
		int lblStartWhile = getIp();
		parse("while CONDITION EOL");
		Instruction jz_lblEndWhile = jz();
		//STATEMENTS
		parseStatements();
		Instruction jmp_lblStartWhile = jmp(lblStartWhile);
		if (noYield || currentScript.getScriptType() == ScriptType.FUNCTION) {
			jmp_lblStartWhile.mode = OPCodeMode.FORWARD;
		}
		int lblEndWhile = getIp();
		jz_lblEndWhile.intVal = lblEndWhile;
		endexcept();
		Instruction jmp_lblEndExcept = jmp();
		//EXCEPTIONS
		int lblExceptionHandler = getIp();
		except_lblExceptionHandler.intVal = lblExceptionHandler;
		parseExceptions();
		parse("end while EOL");
		jmp_lblStartWhile.lineNumber = line;
		int lblEndExcept = getIp();
		jmp_lblEndExcept.intVal = lblEndExcept;
		return replace(start, "WHILE");
	}
	
	private SymbolInstance parseBegin() throws ParseException {
		final int start = it.nextIndex();
		accept("begin");
		SymbolInstance symbol = peek();
		if (symbol.is("loop")) {
			//begin loop STATEMENTS EXCEPTIONS end loop
			Instruction except = except();
			int lblBeginLoop = getIp();
			parse("loop EOL");
			//
			parseStatements();
			//
			Instruction jmp_BeginLoop = jmp(lblBeginLoop);
			int lblExceptionHandler = getIp();
			except.intVal = lblExceptionHandler;
			parseExceptions();
			parse("end loop EOL");
			jmp_BeginLoop.lineNumber = line;
			return replace(start, "LOOP");
		} else if (symbol.is("known")) {
			accept("known");
			symbol = peek();
			if (symbol.is("cinema")) {
				//begin known cinema STATEMENTS end known cinema
				if (inCinemaBlock) {
					throw new ParseError("Already in cinema block", file, line, col);
				}
				inCinemaBlock = true;
				inCameraBlock = true;
				inDialogueBlock = true;
				parse("cinema EOL");
				parseStatements();
				parse("end known cinema EOL");
				inCinemaBlock = false;
				inCameraBlock = false;
				inDialogueBlock = false;
				return replace(start, "KNOWN_CINEMA");
			} else if (symbol.is("dialogue")) {
				//begin known dialogue STATEMENTS end known dialogue
				if (inDialogueBlock) {
					throw new ParseError("Already in dialogue block", file, line, col);
				}
				inDialogueBlock = true;
				parse("dialogue EOL");
				parseStatements();
				parse("end known dialogue EOL");
				inDialogueBlock = false;
				return replace(start, "KNOWN_DIALOGUE");
			} else {
				throw new ParseException("Unexpected token: "+symbol+". Expected: dialogue|cinema", lastParseException, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("cinema")) {
			//begin cinema STATEMENTS end cinema
			if (inCinemaBlock) {
				throw new ParseError("Already in cinema block", file, line, col);
			}
			inCinemaBlock = true;
			inCameraBlock = true;
			inDialogueBlock = true;
			//parse("cinema EOL");
			parse("cinema");
			final int lblRetry2 = getIp();
			sys(START_DIALOGUE);
			jz(lblRetry2);
			final int lblRetry1 = getIp();
			sys(START_CAMERA_CONTROL);
			jz(lblRetry1);
			sys(START_GAME_SPEED);
			pushb(true);
			sys(SET_WIDESCREEN);
			//
			parseStatements();
			//
			if (checkAhead("end cinema with dialogue")) {
				parse("end cinema with dialogue EOL");
				pushb(false);
				sys(SET_WIDESCREEN);
				sys(END_GAME_SPEED);
				sys(END_CAMERA_CONTROL);
				inCameraBlock = false;
				//
				parseStatements();
				//
				parse("end dialogue EOL");
				sys(END_DIALOGUE);
			} else if (checkAhead("end cinema with widescreen")) {
				parse("end cinema with widescreen EOL");
				pushb(false);
				sys(SET_WIDESCREEN);
				sys(END_GAME_SPEED);
				sys(END_CAMERA_CONTROL);
				sys(END_DIALOGUE);
				sys(GAME_HOLD_WIDESCREEN);
			} else {
				parse("end cinema EOL");
				pushb(false);
				sys(SET_WIDESCREEN);
				sys(END_GAME_SPEED);
				sys(END_CAMERA_CONTROL);
				sys(END_DIALOGUE);
			}
			inCinemaBlock = false;
			inCameraBlock = false;
			inDialogueBlock = false;
			return replace(start, "CINEMA");
		} else if (symbol.is("camera")) {
			//begin camera STATEMENTS end camera
			if (inCameraBlock || inDialogueBlock) {
				throw new ParseError("Already in camera block", file, line, col);
			}
			inCameraBlock = true;
			inDialogueBlock = true;
			parse("camera EOL");
			final int lblRetry2 = getIp();
			sys(START_DIALOGUE);
			jz(lblRetry2);
			final int lblRetry1 = getIp();
			sys(START_CAMERA_CONTROL);
			jz(lblRetry1);
			sys(START_GAME_SPEED);
			//
			parseStatements();
			//
			parse("end camera EOL");
			sys(END_GAME_SPEED);
			sys(END_CAMERA_CONTROL);
			sys(END_DIALOGUE);
			inCameraBlock = false;
			inDialogueBlock = false;
			return replace(start, "begin camera");
		} else if (symbol.is("dialogue")) {
			//begin dialogue STATEMENTS end dialogue
			if (inDialogueBlock) {
				throw new ParseError("Already in dialogue block", file, line, col);
			}
			inDialogueBlock = true;
			parse("dialogue EOL");
			final int lblRetry1 = getIp();
			sys(START_DIALOGUE);
			jz(lblRetry1);
			//
			parseStatements();
			//
			parse("end dialogue EOL");
			sys(END_DIALOGUE);
			inDialogueBlock = false;
			return replace(start, "begin dialogue");
		} else if (symbol.is("dual")) {
			//begin dual camera to OBJECT OBJECT EOL STATEMENTS EOL end dual camera
			parse("dual camera to OBJECT OBJECT EOL");
			sys(START_DUAL_CAMERA);
			//
			parseStatements();
			//
			parse("end dual camera EOL");
			sys(RELEASE_DUAL_CAMERA);
			return replace(start, "begin dual camera");
		} else if (symbol.is("cannon")) {
			//begin cannon
			parse("cannon EOL");
			sys(START_CANNON_CAMERA);
			//
			parseStatements();
			//
			parse("end cannon EOL");
			sys(END_CANNON_CAMERA);
			return replace(start, "begin dual camera");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: loop|cinema|camera|dialogue|known|dual", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseExceptions() throws ParseException {
		final int start = it.nextIndex();
		List<Instruction> jmps_EndExcept = new LinkedList<>();
		SymbolInstance symbol = peek();
		while (symbol.is("when") || symbol.is("until")) {
			parseException(jmps_EndExcept);
			symbol = peek();
		}
		//EXCEPTIONS
		iterexcept();
		int lblEndExcept = getIp();
		for (Instruction jmp_EndExcept : jmps_EndExcept) {
			jmp_EndExcept.intVal = lblEndExcept;
		}
		return replace(start, "EXCEPTIONS");
	}
	
	private SymbolInstance parseException(List<Instruction> jmps_EndExcept) throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		if (symbol.is("when")) {
			//when CONDITION
			parse("when CONDITION EOL");
			Instruction jz_condNotMatched = jz();
			//
			parseStatements();
			//
			int lblEndWhen = getIp();
			jz_condNotMatched.intVal = lblEndWhen;
			return replace(start, "WHEN");
		} else if (symbol.is("until")) {
			//until CONDITION
			parse("until CONDITION EOL");
			Instruction jz_untilNotMatched = jz();
			pushb(false);
			sys(SET_WIDESCREEN);
			sys(END_GAME_SPEED);
			sys(END_DIALOGUE);
			sys(END_CAMERA_CONTROL);
			//
			parseStatements();
			//
			brkexcept();
			Instruction jmp_EndExcept = jmp();
			int lblUntilNotMatched = getIp();
			jz_untilNotMatched.intVal = lblUntilNotMatched;
			jmps_EndExcept.add(jmp_EndExcept);
			return replace(start, "UNTIL");
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: when|until", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	private SymbolInstance parseExpression(boolean fail) throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		SymbolInstance newSym = parseExpression1();
		while (newSym != null && newSym != symbol) {
			symbol = newSym;
			prev();
			newSym = parseExpression1();
		}
		seek(start);
		symbol = peek();
		if ("EXPRESSION".equals(symbol.symbol.keyword)) {
			next();
			return symbol;
		}
		if (fail) {
			if (lastParseException != null) throw lastParseException;
			symbol = peek();
			throw new ParseException("Expected: EXPRESSION", lastParseException, file, symbol.token.line, symbol.token.col);
		} else {
			seek(start);
			return null;
		}
	}
	
	private SymbolInstance parseExpression1() throws ParseException {
		final int start = it.nextIndex();
		//final int startIp = getIp();
		try {
			SymbolInstance symbol = peek();
			if ("EXPRESSION".equals(symbol.symbol.keyword)) {
				next();
				SymbolInstance operator = next();
				if (operator.is("*")) {
					symbol = parseExpression1();
					if (symbol == null) {
						throw new ParseException("Expected: EXPRESSION", lastParseException, file, line, col);
					}
					//EXPRESSION * EXPRESSION
					mul();
					return replace(start, "EXPRESSION");
				} else if (operator.is("/")) {
					symbol = parseExpression1();
					if (symbol == null) {
						throw new ParseException("Expected: EXPRESSION", lastParseException, file, line, col);
					}
					//EXPRESSION / EXPRESSION
					div();
					return replace(start, "EXPRESSION");
				} else if (operator.is("%")) {
					symbol = parseExpression1();
					if (symbol == null) {
						throw new ParseException("Expected: EXPRESSION", lastParseException, file, line, col);
					}
					//EXPRESSION % EXPRESSION
					mod();
					return replace(start, "EXPRESSION");
				} else if (operator.is("+") || operator.is("-")) {
					//parseExpression(true);	<- good, but doesn't match the original compiler behavior
					//> alternate method
					symbol = parseExpression1();
					if (symbol == null) {
						symbol = peek();
						throw new ParseException("Expected: EXPRESSION", lastParseException, file, line, col);
					}
					/*symbol = peek();
					if (symbol.is("*") || symbol.is("/") || symbol.is("%")) {
						prev();
						parseExpression(true);
					}*/
					symbol = peek();
					while (symbol.is("*") || symbol.is("/") || symbol.is("%")) {
						prev();
						parseExpression1();
						symbol = peek();
					}
					//< alternate method
					if (operator.is("+")) {
						//EXPRESSION + EXPRESSION
						addf();
					} else {
						//EXPRESSION - EXPRESSION
						subf();
					}
					return replace(start, "EXPRESSION");
				} else {
					seek(start);
					return peek();
				}
			} else if (symbol.is("&")) {
				//& VARIABLE
				accept("&");
				symbol = accept(TokenType.IDENTIFIER);
				String name = symbol.token.value;
				SymbolInstance sInst = peek();
				if (sInst.is("[")) {
					accept("[");
					if (checkAhead("NUMBER ]")) {
						//IDENTIFIER\[NUMBER\]
						sInst = next();
						accept("]");
						int index = sInst.token.intVal();
						pushvAddr(name, index);
						ref_push();
					} else {
						//IDENTIFIER\[EXPRESSION\]
						parseExpression(true);
						accept("]");
						pushvAddr(name, 0);
						ref_add_pushv2();
					}
				} else {
					pushvAddr(name);
					ref_push();
				}
				return replace(start, "EXPRESSION");
			} else if (symbol.is("remove")) {
				parse("remove resource CONST_EXPR EXPRESSION from OBJECT");
				//remove resource CONST_EXPR EXPRESSION from OBJECT
				sys(REMOVE_RESOURCE);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("add")) {
				parse("add resource CONST_EXPR EXPRESSION to OBJECT");
				//add resource CONST_EXPR EXPRESSION to OBJECT
				sys(ADD_RESOURCE);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("alignment")) {
				parse("alignment of player");
				//alignment of player
				pushi(0);
				sys2(GET_ALIGNMENT);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("raw") || symbol.is("influence")) {
				//[raw] influence at COORD_EXPR
				pushf(1);	//fixed player
				parse("[raw] influence at COORD_EXPR");
				sys(GET_INFLUENCE);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("get")) {
				accept("get");
				symbol = peek();
				if (symbol.is("player")) {
					parse("player EXPRESSION");
					symbol = peek();
					if (symbol.is("raw") || symbol.is("influence")) {
						parse("[raw] influence at COORD_EXPR");
						//get player EXPRESSION [raw] influence at COORD_EXPR
						sys(GET_INFLUENCE);
						return replace(start, "EXPRESSION");
					} else if (symbol.is("town")) {
						parse("town total");
						//get player EXPRESSION town total
						sys(GET_PLAYER_TOWN_TOTAL);
						return replace(start, "EXPRESSION");
					} else if (symbol.is("time")) {
						parse("time since last spell cast");
						//get player EXPRESSION time since last spell cast
						sys(PLAYER_SPELL_CAST_TIME);
						return replace(start, "EXPRESSION");
					} else if (symbol.is("ally")) {
						parse("ally percentage with player EXPRESSION");
						//get player EXPRESSION ally percentage with player EXPRESSION
						sys(GET_PLAYER_ALLY);
						return replace(start, "EXPRESSION");
					}
				} else if (symbol.is("time")) {
					parse("time since");
					symbol = peek();
					if (symbol.is("player")) {
						parse("player EXPRESSION attacked OBJECT");
						//get time since player EXPRESSION attacked OBJECT
						sys(GET_TIME_SINCE_OBJECT_ATTACKED);
						return replace(start, "EXPRESSION");
					} else {
						parse("CONSTANT event");
						//get time since HELP_EVENT_TYPE event
						sys(GET_TIME_SINCE);
						return replace(start, "EXPRESSION");
					}
				} else if (symbol.is("resource")) {
					parse("resource CONST_EXPR in OBJECT");
					//get resource CONST_EXPR in OBJECT
					sys(GET_RESOURCE);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("number")) {
					parse("number of CONST_EXPR for OBJECT");
					//get number of CONST_EXPR for OBJECT
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "EXPRESSION");
				} else if (symbol.is("inclusion")) {
					parse("inclusion distance");
					//get inclusion distance
					sys(GET_INCLUSION_DISTANCE);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("slowest")) {
					parse("slowest speed in OBJECT");
					//get slowest speed in OBJECT
					sys(GET_SLOWEST_SPEED);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("distance")) {
					parse("distance from COORD_EXPR to COORD_EXPR");
					//get distance from COORD_EXPR to COORD_EXPR
					sys(GET_DISTANCE);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("mana")) {
					parse("mana for spell CONST_EXPR");
					//get mana for spell CONST_EXPR
					sys(GET_MANA_FOR_SPELL);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("building")) {
					parse("building and villager health total in OBJECT");
					//get building and villager health total in OBJECT
					sys(GET_TOWN_AND_VILLAGER_HEALTH_TOTAL);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("size")) {
					parse("size of OBJECT PLAYING_SIDE team");
					//get size of OBJECT PLAYING_SIDE team
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "EXPRESSION");
				} else if (symbol.is("worship")) {
					parse("worship deaths in OBJECT");
					//get worship deaths in OBJECT
					sys(GET_TOWN_WORSHIP_DEATHS);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("computer")) {
					parse("computer player EXPRESSION attitude to player EXPRESSION");
					//get computer player EXPRESSION attitude to player EXPRESSION
					sys(GET_COMPUTER_PLAYER_ATTITUDE);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("moon")) {
					parse("moon percentage");
					//get moon percentage
					sys(GET_MOON_PERCENTAGE);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("game")) {
					parse("game time");
					//get game time
					sys(GET_GAME_TIME);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("real")) {
					accept("real");
					symbol = peek();
					if (symbol.is("time")) {
						accept("time");
						//get real time
						sys(GET_REAL_TIME);
						return replace(start, "EXPRESSION");
					} else if (symbol.is("day")) {
						accept("day");
						//get real day
						throw new ParseException("Statement not implemented", file, line, col);
						//return replace(start, "EXPRESSION");
					} else if (symbol.is("weekday")) {
						accept("weekday");
						//get real weekday
						throw new ParseException("Statement not implemented", file, line, col);
						//return replace(start, "EXPRESSION");
					} else if (symbol.is("month")) {
						accept("month");
						//get real month
						sys(GET_REAL_MONTH);
						return replace(start, "EXPRESSION");
					} else if (symbol.is("year")) {
						accept("year");
						//get real year
						sys(GET_REAL_YEAR);
						return replace(start, "EXPRESSION");
					}
				} else if (symbol.is("countdown")) {
					parse("countdown timer time");
					//get countdown timer time
					sys(GET_COUNTDOWN_TIMER_TIME);
					return replace(start, "EXPRESSION");
				} else if (checkAhead("CONSTANT of")) {
					//[get] CONSTANT of OBJECT
					parse("CONSTANT of OBJECT");
					sys(GET_PROPERTY);
					return replace(start, "EXPRESSION");
				} else {
					final int checkpoint = it.nextIndex();
					final int checkpointIp = getIp();
					SymbolInstance checkpointPreserve = peek();
					symbol = parseObject(false);
					if (symbol != null) {
						symbol = peek();
						if (symbol.is("music")) {
							parse("music distance");
							//get OBJECT music distance
							sys(GET_MUSIC_OBJ_DISTANCE);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("interaction")) {
							parse("interaction magnitude");
							//get OBJECT interaction magnitude
							sys(GET_INTERACTION_MAGNITUDE);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("time")) {
							accept("time");
							symbol = peek();
							if (symbol.is("remaining")) {
								accept("remaining");
								//get OBJECT time remaining
								sys(GET_TIMER_TIME_REMAINING);
								return replace(start, "EXPRESSION");
							} else if (symbol.is("since")) {
								parse("since set");
								//get OBJECT time since set
								sys(GET_TIMER_TIME_SINCE_SET);
								return replace(start, "EXPRESSION");
							}
						} else if (symbol.is("fight")) {
							parse("fight queue hits");
							//get OBJECT fight queue hits
							sys(CREATURE_FIGHT_QUEUE_HITS);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("walk")) {
							parse("walk path percentage");
							//get OBJECT walk path percentage
							sys(GET_WALK_PATH_PERCENTAGE);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("mana")) {
							parse("mana total");
							//get OBJECT mana total
							sys(GET_MANA);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("played")) {
							parse("played percentage");
							//get OBJECT played percentage
							sys(PLAYED_PERCENTAGE);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("belief")) {
							parse("belief for player EXPRESSION");
							//get OBJECT belief for player EXPRESSION
							sys(BELIEF_FOR_PLAYER);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("help")) {
							accept("help");
							//get OBJECT help
							sys(GET_HELP);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("first")) {
							parse("first help");
							//get OBJECT first help
							sys(GET_FIRST_HELP);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("last")) {
							parse("last help");
							//get OBJECT last help
							sys(GET_LAST_HELP);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("fade")) {
							accept("fade");
							//get OBJECT fade
							sys(GET_OBJECT_FADE);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("info")) {
							parse("info bits");
							//get OBJECT info bits
							sys(OBJECT_INFO_BITS);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("desire")) {
							parse("desire CONST_EXPR");
							//get OBJECT desire CONST_EXPR
							throw new ParseException("Statement not implemented", file, line, col);
							//return replace(start, "EXPRESSION");
						} else if (symbol.is("sacrifice")) {
							parse("sacrifice total");
							//get OBJECT sacrifice total
							sys(GET_SACRIFICE_TOTAL);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("spell")) {
							parse("spell CONST_EXPR skill");
							//get OBJECT spell MAGIC_TYPE skill
							sys(GET_CREATURE_SPELL_SKILL);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("score")) {
							accept("score");
							//get OBJECT score
							sys(GET_OBJECT_SCORE);
							return replace(start, "EXPRESSION");
						}
						revert(checkpoint, checkpointIp, checkpointPreserve);
					}
					symbol = parseConstExpr(false);
					if (symbol != null) {
						symbol = peek();
						if (symbol.is("music")) {
							parse("music distance");
							//get CONST_EXPR music distance
							sys(GET_MUSIC_ENUM_DISTANCE);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("events")) {
							parse("events per second");
							//get HELP_EVENT_TYPE events per second
							sys(GET_EVENTS_PER_SECOND);
							return replace(start, "EXPRESSION");
						} else if (symbol.is("total")) {
							parse("total event|events");
							//get HELP_EVENT_TYPE total event|events
							sys(GET_TOTAL_EVENTS);
							return replace(start, "EXPRESSION");
						}
						revert(checkpoint, checkpointIp, checkpointPreserve);
					}
				}
			} else if (symbol.is("land")) {
				parse("land height at COORD_EXPR");
				//land height at COORD_EXPR
				sys(GET_LAND_HEIGHT);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("time")) {
				accept("time");
				//time
				sys(DLL_GETTIME);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("number")) {
				accept("number");
				symbol = peek();
				if (symbol.is("from")) {
					parse("from EXPRESSION to EXPRESSION");
					//number from EXPRESSION to EXPRESSION
					sys(RANDOM);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("of")) {
					accept("of");
					symbol = peek();
					if (symbol.is("mouse")) {
						parse("mouse buttons");
						//number of mouse buttons
						sys(NUM_MOUSE_BUTTONS);
						return replace(start, "EXPRESSION");
					} else if (symbol.is("times")) {
						parse("times action CONST_EXPR by OBJECT");
						//number of times action CONST_EXPR by OBJECT
						sys(GET_ACTION_COUNT);
						return replace(start, "EXPRESSION");
					}
				}
			} else if (symbol.is("size")) {
				parse("size of OBJECT");
				//size of OBJECT
				sys(ID_SIZE);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("adult")) {
				accept("adult");
				symbol = peek();
				if (symbol.is("size")) {
					parse("size of OBJECT");
					//adult size of OBJECT
					sys(ID_ADULT_SIZE);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("capacity")) {
					parse("capacity of OBJECT");
					//adult capacity of OBJECT
					sys(OBJECT_ADULT_CAPACITY);
					return replace(start, "EXPRESSION");
				}
			} else if (symbol.is("capacity")) {
				parse("capacity of OBJECT");
				//capacity of OBJECT
				sys(OBJECT_CAPACITY);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("poisoned")) {
				parse("poisoned size of OBJECT");
				//poisoned size of OBJECT
				sys(ID_POISONED_SIZE);
				return replace(start, "EXPRESSION");
			} else if (symbol.is("sqrt")) {
				//sqrt EXPRESSION
				parse("sqrt EXPRESSION");
				sqrt();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("tan")) {
				//tan EXPRESSION
				parse("tan EXPRESSION");
				tan();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("sin")) {
				//sin EXPRESSION
				parse("sin EXPRESSION");
				sin();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("cos")) {
				//cos EXPRESSION
				parse("cos EXPRESSION");
				cos();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("arctan")) {
				//arctan EXPRESSION
				parse("arctan EXPRESSION");
				atan();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("arcsin")) {
				//arcsin EXPRESSION
				parse("arcsin EXPRESSION");
				asin();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("arccos")) {
				//arccos EXPRESSION
				parse("arccos EXPRESSION");
				acos();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("arctan2")) {
				//arctan2 EXPRESSION over EXPRESSION
				parse("arctan2 EXPRESSION over EXPRESSION");
				atan2();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("abs")) {
				//abs EXPRESSION
				parse("abs EXPRESSION");
				abs();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("mouse")) {
				parse("mouse percentage");
				symbol = peek();
				if (symbol.is("across")) {
					accept("across");
					//mouse percentage across
					sys(GET_MOUSE_ACROSS);
					return replace(start, "EXPRESSION");
				} else if (symbol.is("down")) {
					accept("down");
					//mouse percentage down
					sys(GET_MOUSE_DOWN);
					return replace(start, "EXPRESSION");
				}
			} else if (symbol.is("-")) {
				//-EXPRESSION
				accept("-");
				parseExpression1();	//<- important for precedence
				neg();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("variable")) {
				parse("variable CONST_EXPR");
				//variable CONST_EXPR
				castf();
				return replace(start, "EXPRESSION");
			} else if (symbol.is("(")) {
				parse("( EXPRESSION )");
				//(EXPRESSION)
				return replace(start, "EXPRESSION");
			} else if (symbol.is(TokenType.NUMBER)) {
				symbol = accept(TokenType.NUMBER);
				float val = symbol.token.floatVal();
				//NUMBER
				pushf(val);
				return replace(start, "EXPRESSION");
			} else if (symbol.is(TokenType.IDENTIFIER)) {
				symbol = peek(1);
				if (symbol.is("of")) {
					//[get] CONSTANT of OBJECT
					SymbolInstance id1 = accept(TokenType.IDENTIFIER);
					int property = getConstant(id1.token.value);
					pushi(property);
					parse("of OBJECT");
					sys(GET_PROPERTY);
					return replace(start, "EXPRESSION");
				} else if (options.returnEnabled && symbol.is("(")) {
					//IDENTIFIER(PARAMETERS)
					SymbolInstance id1 = accept(TokenType.IDENTIFIER);
					String scriptName = id1.token.value;
					Script script = scriptDefinitions.get(scriptName);
					if (script == null) {
						throw new ParseException("Undefined function: "+scriptName, lastParseException, file, symbol.token.line, symbol.token.col);
					} else if (script.getScriptType() != ScriptType.FUNCTION) {
						throw new ParseException("Script "+scriptName+" is not a function", lastParseException, file, symbol.token.line, symbol.token.col);
					}
					int argc = parseParameters();
					pushvAddr("_returned");
					ref_push();
					argc++;
					if (argc != script.getParameterCount()) {
						throw new ParseException("Script "+scriptName+" expects "+script.getParameterCount()+" parameters", lastParseException, file, symbol.token.line, symbol.token.col);
					}
					call(scriptName, argc);
					pushvVal("_returned");
					return replace(start, "EXPRESSION");
				} else {
					//IDENTIFIER
					//IDENTIFIER\[NUMBER\]
					//IDENTIFIER\[EXPRESSION\]
					parse("VARIABLE");
					return replace(start, "EXPRESSION");
				}
			}
		} catch (ParseException e) {
			lastParseException = e;
		}
		//revert(start, startIp);
		seek(start);
		return null;
	}
	
	private SymbolInstance parseCondition(boolean fail) throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		SymbolInstance newSym = parseCondition1();
		while (newSym != null && newSym != symbol) {
			symbol = newSym;
			prev();
			newSym = parseCondition1();
		}
		seek(start);
		symbol = peek();
		if ("CONDITION".equals(symbol.symbol.keyword)) {
			next();
			return symbol;
		}
		if (fail) {
			if (lastParseException != null) throw lastParseException;
			symbol = peek();
			throw new ParseException("Expected: CONDITION", lastParseException, file, symbol.token.line, symbol.token.col);
		} else {
			seek(start);
			return null;
		}
	}
	
	private SymbolInstance parseCondition1() throws ParseException {
		final int start = it.nextIndex();
		final int startIp = getIp();
		final SymbolInstance startPreserve = peek();
		try {
			SymbolInstance symbol = peek();
			if ("CONDITION".equals(symbol.symbol.keyword)) {
				next();
				symbol = next();
				if (symbol.is("and")) {
					symbol = parseCondition1();
					if (symbol == null) {
						symbol = peek();
						throw new ParseException("Expected: CONDITION", lastParseException, file, line, col);
					}
					//CONDITION and CONDITION
					and();
					return replace(start, "CONDITION");
				} else if (symbol.is("or")) {
					//CONDITION or CONDITION
					//parseCondition(true);	//<- good, but doesn't match the original compiler behavior
					//> alternate method
					symbol = parseCondition1();
					if (symbol == null) {
						symbol = peek();
						throw new ParseException("Expected: CONDITION", lastParseException, file, line, col);
					}
					/*symbol = peek();
					if (symbol.is("and")) {
						prev();
						parseCondition(true);
					}*/
					symbol = peek();
					while (symbol.is("and")) {
						prev();
						parseCondition1();
						symbol = peek();
					}
					//< alternate method
					or();
					return replace(start, "CONDITION");
				} else {
					seek(start);
					return peek();
				}
			} else if (symbol.is("key")) {
				parse("key CONST_EXPR down");
				//key CONST_EXPR down
				sys(KEY_DOWN);
				return replace(start, "CONDITION");
			} else if (symbol.is("inside")) {
				parse("inside temple");
				//inside temple
				sys(INSIDE_TEMPLE);
				return replace(start, "CONDITION");
			} else if (symbol.is("within")) {
				parse("within rotation");
				//within rotation
				sys(WITHIN_ROTATION);
				return replace(start, "CONDITION");
			} else if (symbol.is("hand")) {
				parse("hand demo");
				symbol = peek();
				if (symbol.is("played")) {
					accept("played");
					//hand demo played
					sys(IS_PLAYING_HAND_DEMO);
					return replace(start, "CONDITION");
				} else if (symbol.is("trigger")) {
					accept("trigger");
					//hand demo trigger
					sys(HAND_DEMO_TRIGGER);
					return replace(start, "CONDITION");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: played|trigger", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("jc")) {
				parse("jc special CONST_EXPR played");
				//jc special CONST_EXPR played
				throw new ParseException("Statement not implemented", file, line, col);
				//return replace(start, "CONDITION");
			} else if (symbol.is("fire")) {
				parse("fire near COORD_EXPR radius EXPRESSION");
				//fire near COORD_EXPR radius EXPRESSION
				sys(IS_FIRE_NEAR);
				return replace(start, "CONDITION");
			} else if (symbol.is("spell")) {
				accept("spell");
				symbol = peek();
				if (symbol.is("wind")) {
					parse("wind near COORD_EXPR radius EXPRESSION");
					//spell wind near COORD_EXPR radius EXPRESSION
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "CONDITION");
				} else if (symbol.is("charging")) {
					accept("charging");
					//spell charging
					throw new ParseException("Statement not implemented", file, line, col);
					//return replace(start, "CONDITION");
				} else {
					parse("CONST_EXPR for player EXPRESSION");
					//spell CONST_EXPR for player EXPRESSION
					sys(HAS_PLAYER_MAGIC);
					return replace(start, "CONDITION");
				}
			} else if (symbol.is("camera")) {
				accept("camera");
				symbol = peek();
				if (symbol.is("ready")) {
					accept("ready");
					//camera ready
					sys(HAS_CAMERA_ARRIVED);
					return replace(start, "CONDITION");
				} else if (symbol.is("not")) {
					parse("not ready");
					//camera not ready
					sys(HAS_CAMERA_ARRIVED);
					not();
					return replace(start, "CONDITION");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: ready|not", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("widescreen")) {
				accept("widescreen");
				symbol = peek();
				if (symbol.is("ready")) {
					accept("ready");
					//widescreen ready
					sys(WIDESCREEN_TRANSISTION_FINISHED);
					return replace(start, "CONDITION");
				} else if (symbol.is("transition")) {
					parse("transition complete");
					//widescreen transition complete
					sys(WIDESCREEN_TRANSISTION_FINISHED);
					return replace(start, "CONDITION");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: ready|transition", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("fade")) {
				parse("fade ready");
				//fade ready
				sys(FADE_FINISHED);
				return replace(start, "CONDITION");
			} else if (symbol.is("dialogue")) {
				accept("dialogue");
				symbol = peek();
				if (symbol.is("ready")) {
					accept("ready");
					//dialogue ready
					sys(IS_DIALOGUE_READY);
					return replace(start, "CONDITION");
				} else if (symbol.is("not")) {
					parse("not ready");
					//dialogue not ready
					sys(IS_DIALOGUE_READY);
					not();
					return replace(start, "CONDITION");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: ready|not", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("computer")) {
				parse("computer player EXPRESSION ready");
				//computer player EXPRESSION ready
				sys(COMPUTER_PLAYER_READY);
				return replace(start, "CONDITION");
			} else if (symbol.is("player")) {
				accept("player");
				symbol = peek();
				if (symbol.is("has")) {
					parse("has mouse wheel");
					//player has mouse wheel
					sys(HAS_MOUSE_WHEEL);
					return replace(start, "CONDITION");
				} else {
					parse("EXPRESSION wind resistance");
					//player EXPRESSION wind resistance
					throw new ParseException("Statement not implemented", lastParseException, file, line, col);
					//return replace(start, "CONDITION");
				}
			} else if (symbol.is("creature")) {
				accept("creature");
				symbol = peek();
				if (symbol.is("help")) {
					parse("help on");
					//creature help on
					sys(CREATURE_HELP_ON);
					return replace(start, "CONDITION");
				} else {
					parse("CONST_EXPR is available");
					//creature CONST_EXPR is available
					sys(IS_CREATURE_AVAILABLE);
					return replace(start, "CONDITION");
				}
			} else if (symbol.is("get")) {
				parse("get desire of OBJECT is CONST_EXPR");
				//get desire of OBJECT is CONST_EXPR
				throw new ParseException("Statement not implemented", lastParseException, file, line, col);
				//return replace(start, "CONDITION");
			} else if (symbol.is("read")) {
				accept("read");
				//read
				sys(TEXT_READ);
				return replace(start, "CONDITION");
			} else if (symbol.is("help")) {
				parse("help system on");
				//help system on
				sys(HELP_SYSTEM_ON);
				return replace(start, "CONDITION");
			} else if (symbol.is("immersion")) {
				parse("immersion exists");
				//immersion exists
				sys(IMMERSION_EXISTS);
				return replace(start, "CONDITION");
			} else if (symbol.is("sound")) {
				accept("sound");
				symbol = peek();
				if (symbol.is("exists")) {
					accept("exists");
					//sound exists
					sys(SOUND_EXISTS);
					return replace(start, "CONDITION");
				} else {
					parseConstExpr(true);
					symbol = peek();
					if (symbol.is("playing")) {
						pushi(DEFAULT_SOUNDBANK_NAME);
					} else {
						parseConstExpr(true);
					}
					accept("playing");
					//sound CONST_EXPR [CONST_EXPR] playing
					sys(GAME_SOUND_PLAYING);
					return replace(start, "CONDITION");
				}
			} else if (symbol.is("specific")) {
				parse("specific spell charging");
				//specific spell charging
				throw new ParseException("Statement not implemented", lastParseException, file, line, col);
				//return replace(start, "CONDITION");
			} else if (symbol.is("music")) {
				accept("music");
				symbol = peek();
				if (symbol.is("line")) {
					parse("line EXPRESSION");
					//music line EXPRESSION
					sys(LAST_MUSIC_LINE);
					return replace(start, "CONDITION");
				} else {
					//music CONST_EXPR played
					symbol = parseConstExpr(true);
					accept("played");
					sys(MUSIC_PLAYED2);
					return replace(start, "CONDITION");
				}
			} else if (symbol.is("in")) {
				parse("in widescreen");
				//in widescreen
				sys(IN_WIDESCREEN);
				return replace(start, "CONDITION");
			} else if (symbol.is("mouse")) {
				parse("mouse left|right button down");
				//mouse left|right button down
				sys(MOUSE_DOWN);
				return replace(start, "CONDITION");
			} else if (symbol.is("not")) {
				//not CONDITION
				accept("not");
				parseCondition1();	//<- important for precedence
				not();
				return replace(start, "CONDITION");
			} else if (symbol.is("say")) {
				//say sound CONST_EXPR playing
				pushb(false);
				parse("say sound CONST_EXPR playing");
				sys(SAY_SOUND_EFFECT_PLAYING);
				return replace(start, "CONDITION");
			} else if (symbol.is("can")) {
				parse("can skip");
				symbol = peek();
				if (symbol.is("tutorial")) {
					accept("tutorial");
					//can skip tutorial
					sys(CAN_SKIP_TUTORIAL);
					return replace(start, "CONDITION");
				} else if (symbol.is("creature")) {
					parse("creature training");
					//can skip creature training
					sys(CAN_SKIP_CREATURE_TRAINING);
					return replace(start, "CONDITION");
				}
			} else if (symbol.is("is")) {
				parse("is keeping old creature");
				//is keeping old creature
				sys(IS_KEEPING_OLD_CREATURE);
				return replace(start, "CONDITION");
			} else if (symbol.is("current")) {
				parse("current profile has creature");
				//current profile has creature
				sys(CURRENT_PROFILE_HAS_CREATURE);
				return replace(start, "CONDITION");
			} else if (symbol.is("(")) {
				parse("( CONDITION )");
				//(CONDITION)
				return replace(start, "CONDITION");
			} else if (checkAhead("ANY spirit")) {
				parse("SPIRIT_TYPE spirit");
				symbol = peek();
				if (symbol.is("played")) {
					accept("played");
					//SPIRIT_TYPE spirit played
					sys(SPIRIT_PLAYED);
					return replace(start, "CONDITION");
				} else if (symbol.is("speaks")) {
					parse("speaks CONST_EXPR");
					//SPIRIT_TYPE spirit speaks CONST_EXPR
					sys(SPIRIT_SPEAKS);
					return replace(start, "CONDITION");
				}
			}
		} catch (ParseException e) {
			lastParseException = e;
		}
		revert(start, startIp, startPreserve);
		try {	
			SymbolInstance symbol = parseObject(false);
			if (symbol != null) {
				symbol = peek();
				if (symbol.is("active")) {
					accept("active");
					//OBJECT active
					sys(IS_ACTIVE);
					return replace(start, "CONDITION");
				} else if (symbol.is("viewed")) {
					accept("viewed");
					//OBJECT viewed
					sys(GAME_THING_FIELD_OF_VIEW);
					return replace(start, "CONDITION");
				} else if (symbol.is("can")) {
					parse("can view camera in EXPRESSION degrees");
					//OBJECT can view camera in EXPRESSION degrees
					sys(GAME_THING_CAN_VIEW_CAMERA);
					return replace(start, "CONDITION");
				} else if (symbol.is("within")) {
					parse("within flock distance");
					//OBJECT within flock distance
					sys(FLOCK_WITHIN_LIMITS);
					return replace(start, "CONDITION");
				} else if (symbol.is("clicked")) {
					accept("clicked");
					//OBJECT clicked
					sys(GAME_THING_CLICKED);
					return replace(start, "CONDITION");
				} else if (symbol.is("hit")) {
					accept("hit");
					//OBJECT hit
					sys(GAME_THING_HIT);
					return replace(start, "CONDITION");
				} else if (symbol.is("locked")) {
					parse("locked interaction");
					//OBJECT locked interaction
					sys(IS_LOCKED_INTERACTION);
					return replace(start, "CONDITION");
				} else if (symbol.is("not")) {
					accept("not");
					symbol = peek();
					if (symbol.is("clicked")) {
						accept("clicked");
						//OBJECT not clicked
						sys(GAME_THING_CLICKED);
						not();
						return replace(start, "CONDITION");
					} else if (symbol.is("viewed")) {
						accept("viewed");
						//OBJECT not viewed
						sys(GAME_THING_FIELD_OF_VIEW);
						not();
						return replace(start, "CONDITION");
					} else if (symbol.is("in")) {
						parse("in OBJECT");
						symbol = peek();
						if (symbol.is("hand")) {
							accept("hand");
							//OBJECT not in OBJECT hand
							sys(IN_CREATURE_HAND);
							not();
							return replace(start, "CONDITION");
						} else {
							//OBJECT not in OBJECT 
							sys(FLOCK_MEMBER);
							not();
							return replace(start, "CONDITION");
						}
					} else if (symbol.is("exists")) {
						accept("exists");
						//OBJECT not exists
						casto();
						sys(THING_VALID);
						not();
						return replace(start, "CONDITION");
					}
				} else if (symbol.is("played")) {
					accept("played");
					//OBJECT played
					sys(PLAYED);
					return replace(start, "CONDITION");
				} else if (symbol.is("music")) {
					parse("music played");
					//OBJECT music played
					throw new ParseException("Statement not implemented", lastParseException, file, line, col);
					//return replace(start, "CONDITION");
				} else if (symbol.is("cast")) {
					parse("cast by OBJECT");
					//OBJECT cast by OBJECT
					sys(OBJECT_CAST_BY_OBJECT);
					return replace(start, "CONDITION");
				} else if (symbol.is("poisoned")) {
					accept("poisoned");
					//OBJECT poisoned
					sys(IS_POISONED);
					return replace(start, "CONDITION");
				} else if (symbol.is("skeleton")) {
					accept("skeleton");
					//OBJECT skeleton
					sys(IS_SKELETON);
					return replace(start, "CONDITION");
				} else if (symbol.is("type")) {
					parse("type CONST_EXPR");
					symbol = parseConstExpr(false);
					if (symbol == null) {
						pushi(DEFAULT_SUBTYPE_NAME);
					}
					//OBJECT type TYPE [CONST_EXPR]
					sys(IS_OF_TYPE);
					return replace(start, "CONDITION");
				} else if (symbol.is("on")) {
					parse("on fire");
					//OBJECT on fire
					sys(IS_ON_FIRE);
					return replace(start, "CONDITION");
				} else if (symbol.is("in")) {
					parse("in OBJECT");
					symbol = peek();
					if (symbol.is("hand")) {
						accept("hand");
						//OBJECT in OBJECT hand
						sys(IN_CREATURE_HAND);
						return replace(start, "CONDITION");
					} else {
						//OBJECT in OBJECT
						sys(FLOCK_MEMBER);
						return replace(start, "CONDITION");
					}
				} else if (symbol.is("interacting")) {
					parse("interacting with OBJECT");
					//OBJECT interacting with OBJECT
					throw new ParseException("Statement not implemented", lastParseException, file, line, col);
					//return replace(start, "CONDITION");
				} else if (symbol.is("is")) {
					accept("is");
					symbol = peek();
					if (symbol.is("male")) {
						accept("male");
						//OBJECT is male
						sys(SEX_IS_MALE);
						return replace(start, "CONDITION");
					} else if (symbol.is("not")) {
						parse("not CONST_EXPR");
						//OBJECT is not CONST_EXPR
						swap();
						sys2(GET_PROPERTY);
						castb();
						not();
						return replace(start, "CONDITION");
					} else {
						parseConstExpr(true);
						//OBJECT is CONST_EXPR
						swap();
						sys2(GET_PROPERTY);
						castb();
						return replace(start, "CONDITION");
					}
				} else if (symbol.is("exists")) {
					accept("exists");
					//OBJECT exists
					casto();
					sys(THING_VALID);
					return replace(start, "CONDITION");
				} else if (symbol.is("affected")) {
					parse("affected by spell CONST_EXPR");
					//OBJECT affected by spell CONST_EXPR
					sys(IS_AFFECTED_BY_SPELL);
					return replace(start, "CONDITION");
				} else if (symbol.is("leashed")) {
					accept("leashed");
					symbol = peek();
					if (symbol.is("to")) {
						parse("to OBJECT");
						//OBJECT leashed to OBJECT
						sys(IS_LEASHED_TO_OBJECT);
						return replace(start, "CONDITION");
					} else {
						//OBJECT leashed
						sys(IS_LEASHED);
						return replace(start, "CONDITION");
					}
				} else if (symbol.is("fighting")) {
					accept("fighting");
					//OBJECT fighting
					sys(IS_FIGHTING);
					return replace(start, "CONDITION");
				} else if (symbol.is("knows")) {
					parse("knows action CONST_EXPR");
					//OBJECT knows action CREATURE_ACTION_KNOWN_ABOUT
					sys(GET_CREATURE_KNOWS_ACTION);
					return replace(start, "CONDITION");
				} else if (symbol.is("==")) {
					parse("== OBJECT");
					//OBJECT == OBJECT
					eq();
					return replace(start, "CONDITION");
				} else if (symbol.is("!=")) {
					parse("!= OBJECT");
					//OBJECT != OBJECT
					neq();
					return replace(start, "CONDITION");
				}
			}
		} catch (ParseException e) {
			lastParseException = e;
		}
		revert(start, startIp, startPreserve);
		try {
			SymbolInstance symbol = parseCoordExpr(false);
			if (symbol != null) {
				symbol = peek();
				if (symbol.is("viewed")) {
					accept("viewed");
					//COORD_EXPR viewed
					sys(POS_FIELD_OF_VIEW);
					return replace(start, "CONDITION");
				} else if (symbol.is("valid")) {
					parse("valid for creature");
					//COORD_EXPR valid for creature
					sys(POS_VALID_FOR_CREATURE);
					return replace(start, "CONDITION");
				} else if (symbol.is("clicked")) {
					parse("clicked radius EXPRESSION");
					//COORD_EXPR clicked radius EXPRESSION
					sys(POSITION_CLICKED);
					return replace(start, "CONDITION");
				} else if (symbol.is("near")) {
					//COORD_EXPR near COORD_EXPR [radius EXPRESSION]
					parse("near COORD_EXPR");
					sys(GET_DISTANCE);
					parse("[radius EXPRESSION]", 1);
					lt();
					return replace(start, "CONDITION");
				} else if (symbol.is("at")) {
					//COORD_EXPR at COORD_EXPR
					parse("at COORD_EXPR");
					sys(GET_DISTANCE);
					pushf(0);
					eq();
					return replace(start, "CONDITION");
				} else if (symbol.is("not")) {
					accept("not");
					symbol = peek();
					if (symbol.is("viewed")) {
						accept("viewed");
						//COORD_EXPR not viewed
						sys(POS_FIELD_OF_VIEW);
						not();
						return replace(start, "CONDITION");
					} else if (symbol.is("near")) {
						//COORD_EXPR not near COORD_EXPR [radius EXPRESSION]
						parse("near COORD_EXPR");
						sys(GET_DISTANCE);
						parse("[radius EXPRESSION]", 1.0);
						lt();
						not();
						return replace(start, "CONDITION");
					} else if (symbol.is("at")) {
						parse("at COORD_EXPR");
						//COORD_EXPR not at COORD_EXPR
						sys(GET_DISTANCE);
						pushf(0);
						eq();
						not();
						return replace(start, "CONDITION");
					}
				}
			}
		} catch (ParseException e) {
			lastParseException = e;
		}
		revert(start, startIp, startPreserve);
		try {
			SymbolInstance symbol = parseExpression(false);
			if (symbol != null) {
				symbol = peek();
				if (symbol.is("second") || symbol.is("seconds")) {
					parse("second|seconds");
					//EXPRESSION second|seconds
					sleep();
					return replace(start, "CONDITION");
				} else if (symbol.is("==")) {
					parse("== EXPRESSION");
					//EXPRESSION == EXPRESSION
					eq();
					return replace(start, "CONDITION");
				} else if (symbol.is("!=")) {
					parse("!= EXPRESSION");
					//EXPRESSION != EXPRESSION
					neq();
					return replace(start, "CONDITION");
				} else if (symbol.is(">=")) {
					parse(">= EXPRESSION");
					//EXPRESSION >= EXPRESSION
					geq();
					return replace(start, "CONDITION");
				} else if (symbol.is("<=")) {
					parse("<= EXPRESSION");
					//EXPRESSION <= EXPRESSION
					leq();
					return replace(start, "CONDITION");
				} else if (symbol.is(">")) {
					parse("> EXPRESSION");
					//EXPRESSION > EXPRESSION
					gt();
					return replace(start, "CONDITION");
				} else if (symbol.is("<")) {
					parse("< EXPRESSION");
					//EXPRESSION < EXPRESSION
					lt();
					return replace(start, "CONDITION");
				}
			}
		} catch (ParseException e) {
			lastParseException = e;
		}
		revert(start, startIp, startPreserve);
		//seek(start);
		return null;
	}
	
	private SymbolInstance parseObject(boolean fail) throws ParseException {
		final int start = it.nextIndex();
		final int startIp = getIp();
		final SymbolInstance startPreserve = peek();
		SymbolInstance symbol = peek();
		try {
			if (symbol.is("get")) {
				accept("get");
				symbol = peek();
				if (symbol.is("building")) {
					accept("building");
					symbol = peek();
					if (symbol.is("woodpile")) {
						//get building woodpile in OBJECT
						parse("woodpile in OBJECT");
						sys(CALL_BUILDING_WOODPILE_IN_TOWN);
						return replace(start, "OBJECT");
					} else {
						//get building ABODE_NUMBER in OBJECT [min built EXPRESSION] [excluding scripted]
						parse("CONST_EXPR in OBJECT [min built EXPRESSION] [excluding scripted]");
						sys(CALL_BUILDING_IN_TOWN);
						return replace(start, "OBJECT");
					}
				} else if (symbol.is("poisoned")) {
					//get poisoned TYPE [SCRIPT_OBJECT_SUBTYPE] in OBJECT
					parse("poisoned CONST_EXPR");
					symbol = peek();
					if (symbol.is("in")) {
						pushi(DEFAULT_SUBTYPE_NAME);
					} else {
						parseConstExpr(true);
					}
					parse("in OBJECT");
					sys(CALL_POISONED_IN);
					return replace(start, "OBJECT");
				} else if (symbol.is("not")) {
					//get not poisoned SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] in OBJECT [excluding scripted]
					parse("not poisoned CONST_EXPR");
					symbol = peek();
					if (symbol.is("in")) {
						pushi(DEFAULT_SUBTYPE_NAME);
					} else {
						parseConstExpr(true);
					}
					parse("in OBJECT [excluding scripted]");
					sys(CALL_NOT_POISONED_IN);
					return replace(start, "OBJECT");
				} else if (symbol.is("totem")) {
					parse("totem statue in OBJECT");
					//get totem statue in OBJECT
					sys(GET_TOTEM_STATUE);
					return replace(start, "OBJECT");
				} else if (symbol.is("player")) {
					parse("player EXPRESSION creature");
					//get player EXPRESSION creature
					sys(CALL_PLAYER_CREATURE);
					return replace(start, "OBJECT");
				} else if (symbol.is("computer")) {
					parse("computer player EXPRESSION");
					//get computer player EXPRESSION
					sys(CALL_COMPUTER_PLAYER);
					return replace(start, "OBJECT");
				} else if (symbol.is("held")) {
					//get held by OBJECT
					parse("held by OBJECT");
					sys(GET_OBJECT_HELD2);
					return replace(start, "OBJECT");
				} else if (symbol.is("dropped")) {
					parse("dropped by OBJECT");
					//get dropped by OBJECT
					sys(GET_OBJECT_DROPPED);
					return replace(start, "OBJECT");
				} else if (symbol.is("nearest")) {
					parse("nearest town at COORD_EXPR for player EXPRESSION radius EXPRESSION");
					//get nearest town at COORD_EXPR for player EXPRESSION radius EXPRESSION
					sys(GET_NEAREST_TOWN_OF_PLAYER);
					return replace(start, "OBJECT");
				} else if (symbol.is("town")) {
					parse("town with id EXPRESSION");
					//get town with id EXPRESSION
					sys(GET_TOWN_WITH_ID);
					return replace(start, "OBJECT");
				} else if (symbol.is("target")) {
					parse("target object for OBJECT");
					//get target object for OBJECT
					sys(GET_TARGET_OBJECT);
					return replace(start, "OBJECT");
				} else if (symbol.is("arena")) {
					parse("arena at COORD_EXPR radius EXPRESSION");
					//get arena at COORD_EXPR radius EXPRESSION
					throw new ParseException("Statement not implemented", lastParseException, file, line, col);
					//return replace(start, "OBJECT");
				} else if (symbol.is("hit")) {
					parse("hit object");
					//get hit object
					sys(GET_HIT_OBJECT);
					return replace(start, "OBJECT");
				} else if (symbol.is("object")) {
					accept("object");
					symbol = peek();
					if (symbol.is("which")) {
						parse("which hit");
						symbol = peek();
						if (symbol.is("land")) {
							accept("land");
							//get object which hit land
							sys(GET_LAST_OBJECT_WHICH_HIT_LAND);
							return replace(start, "OBJECT");
						} else {
							//get object which hit
							sys(GET_OBJECT_WHICH_HIT);
							return replace(start, "OBJECT");
						}
					} else if (symbol.is("held")) {
						accept("held");
						//get object held
						sys(GET_OBJECT_HELD1);
						return replace(start, "OBJECT");
					} else if (symbol.is("clicked")) {
						accept("clicked");
						//get object clicked
						sys(GET_OBJECT_CLICKED);
						return replace(start, "OBJECT");
					} else if (symbol.is("hand")) {
						parse("hand is over");
						//get object hand is over
						sys(GET_OBJECT_HAND_IS_OVER);
						return replace(start, "OBJECT");
					} else {
						//get object OBJECT leashed to
						parse("OBJECT leashed to");
						sys(GET_OBJECT_OBJECT_LEASHED_TO);
						return replace(start, "OBJECT");
					}
				} else if (symbol.is("football")) {
					parse("football pitch in OBJECT");
					//get football pitch in OBJECT
					sys(GET_FOOTBALL_PITCH);
					return replace(start, "OBJECT");
				} else if (symbol.is("spell")) {
					accept("spell");
					symbol = peek();
					if (symbol.is("icon")) {
						//get spell icon CONST_EXPR in OBJECT
						parse("icon CONST_EXPR in OBJECT");
						sys(GET_SPELL_ICON_IN_TEMPLE);
						return replace(start, "OBJECT");
					} else {
						//get spell CONST_EXPR at COORD_EXPR radius EXPRESSION
						parse("CONST_EXPR at COORD_EXPR radius EXPRESSION");
						sys(SPELL_AT_POINT);
						return replace(start, "OBJECT");
					}
				} else if (symbol.is("first")) {
					parse("first in OBJECT");
					//get first in OBJECT
					sys(GET_FIRST_IN_CONTAINER);
					return replace(start, "OBJECT");
				} else if (symbol.is("next")) {
					parse("next in OBJECT after OBJECT");
					//get next in OBJECT after OBJECT
					sys(GET_NEXT_IN_CONTAINER);
					return replace(start, "OBJECT");
				} else if (symbol.is("dead")) {
					parse("dead at COORD_EXPR radius EXPRESSION");
					//get dead at COORD_EXPR radius EXPRESSION
					sys(GET_DEAD_LIVING);
					return replace(start, "OBJECT");
				} else {
					final int checkpoint = it.nextIndex();
					final int checkpointIp = getIp();
					final SymbolInstance checkpointPreserve = peek();
					symbol = parseConstExpr(false);
					if (symbol != null) {
						symbol = peek();
						if (symbol.is("at") || symbol.is("in") || symbol.is("flying")) {
							pushi(DEFAULT_SUBTYPE_NAME);
						} else {
							parseConstExpr(true);
							symbol = peek();
						}
						if (symbol.is("at")) {
							parse("at COORD_EXPR");
							symbol = peek();
							if (symbol.is("radius")) {
								//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] at COORD_EXPR radius EXPRESSION [excluding scripted]
								parse("radius EXPRESSION [excluding scripted]");
								sys(CALL_NEAR);
								return replace(start, "OBJECT");
							} else {
								//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] at COORD_EXPR [excluding scripted]
								parse("[excluding scripted]");
								sys(CALL);
								return replace(start, "OBJECT");
							}
						} else if (symbol.is("in")) {
							accept("in");
							symbol = peek();
							if (symbol.is("state")) {
								//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] in state CONST_EXPR at COORD_EXPR radius EXPRESSION [excluding scripted]
								parse("state CONST_EXPR at COORD_EXPR radius EXPRESSION [excluding scripted]");
								sys(CALL_NEAR_IN_STATE);
								return replace(start, "OBJECT");
							} else {
								parseObject(true);
								symbol = peek();
								if (symbol.is("at")) {
									//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] in OBJECT at COORD_EXPR radius EXPRESSION [excluding scripted]
									parse("at COORD_EXPR radius EXPRESSION [excluding scripted]");
									sys(CALL_IN_NEAR);
									return replace(start, "OBJECT");
								} else if (symbol.is("not")) {
									//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] in OBJECT not near COORD_EXPR radius EXPRESSION [excluding scripted]
									parse("not near COORD_EXPR radius EXPRESSION [excluding scripted]");
									sys(CALL_IN_NOT_NEAR);
									return replace(start, "OBJECT");
								} else {
									//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] in OBJECT [excluding scripted]
									parse("[excluding scripted]");
									sys(CALL_IN);
									return replace(start, "OBJECT");
								}
							}
						} else if (symbol.is("flying")) {
							parse("flying at COORD_EXPR radius EXPRESSION [excluding scripted]");
							//get SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] flying at COORD_EXPR radius EXPRESSION [excluding scripted]
							sys(CALL_FLYING);
							return replace(start, "OBJECT");
						}
						revert(checkpoint, checkpointIp, checkpointPreserve);
					}
					symbol = parseObject(false);
					if (symbol != null) {
						accept("flock");
						//get OBJECT flock
						sys(GET_OBJECT_FLOCK);
						return replace(start, "OBJECT");
					}
				}
			} else if (symbol.is("create")) {
				accept("create");
				symbol = peek();
				if (symbol.is("random")) {
					parse("random villager of tribe CONST_EXPR at COORD_EXPR");
					//create random villager of tribe CONST_EXPR at COORD_EXPR
					sys(CREATE_RANDOM_VILLAGER_OF_TRIBE);
					return replace(start, "OBJECT");
				} else if (symbol.is("highlight")) {
					parse("highlight CONST_EXPR CONST_EXPR at COORD_EXPR");
					//create highlight CONST_EXPR HIGHLIGHT_INFO at COORD_EXPR
					sys(CREATE_HIGHLIGHT);
					return replace(start, "OBJECT");
				} else if (symbol.is("mist")) {
					parse("mist at COORD_EXPR scale EXPRESSION red EXPRESSION green EXPRESSION blue EXPRESSION transparency EXPRESSION height ratio EXPRESSION");
					//create mist at COORD_EXPR scale EXPRESSION red EXPRESSION green EXPRESSION blue EXPRESSION transparency EXPRESSION height ratio EXPRESSION
					sys(CREATE_MIST);
					return replace(start, "OBJECT");
				} else if (symbol.is("with")) {
					//create with angle EXPRESSION and scale EXPRESSION CONST_EXPR CONST_EXPR at COORD_EXPR
					parse("with angle EXPRESSION and scale EXPRESSION CONST_EXPR");
					symbol = peek();
					if (symbol.is("at")) {
						pushi(DEFAULT_SUBTYPE_NAME);
					} else {
						parseConstExpr(true);
					}
					parse("at COORD_EXPR");
					sys(CREATE_WITH_ANGLE_AND_SCALE);
					return replace(start, "OBJECT");
				} else if (symbol.is("timer")) {
					parse("timer for EXPRESSION second|seconds");
					//create timer for EXPRESSION second|seconds
					sys(CREATE_TIMER);
					return replace(start, "OBJECT");
				} else if (symbol.is("influence")) {
					accept("influence");
					symbol = peek();
					if (symbol.is("on")) {
						parse("on OBJECT [radius EXPRESSION]", 1.0);
						//create influence on OBJECT [radius EXPRESSION]
						pushi(0);	//fixed 0
						pushi(0);	//0 = no anti
						sys(INFLUENCE_OBJECT);
						return replace(start, "OBJECT");
					} else if (symbol.is("at")) {
						parse("at COORD_EXPR [radius EXPRESSION]", 1.0);
						//create influence at COORD_EXPR [radius EXPRESSION]
						pushi(0);	//fixed 0
						pushi(0);	//0 = no anti
						sys(INFLUENCE_POSITION);
						return replace(start, "OBJECT");
					} else {
						throw new ParseException("Unexpected token: "+symbol+". Expected: on|at", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("anti")) {
					parse("anti influence");
					symbol = peek();
					if (symbol.is("on")) {
						parse("on OBJECT [radius EXPRESSION]", 1.0);
						//create anti influence on OBJECT [radius EXPRESSION]
						pushi(0);	//fixed 0
						pushi(1);	//1 = anti
						sys(INFLUENCE_OBJECT);
						return replace(start, "OBJECT");
					} else if (symbol.is("at")) {
						parse("at position COORD_EXPR [radius EXPRESSION]", 1.0);
						//create anti influence at position COORD_EXPR [radius EXPRESSION]
						pushi(0);	//fixed 0
						pushi(1);	//1 = anti
						sys(INFLUENCE_POSITION);
						return replace(start, "OBJECT");
					} else {
						throw new ParseException("Unexpected token: "+symbol+". Expected: on|at", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("special")) {
					parse("special effect");
					symbol = peek();
					if (symbol.is("from")) {
						//create special effect from file STRING
						parse("from file STRING");
						sys(EFFECT_FROM_FILE);
						return replace(start, "OBJECT");
					} else {
						parseConstExpr(true);
						symbol = peek();
						if (symbol.is("at")) {
							//create special effect CONST_EXPR at COORD_EXPR [time EXPRESSION]
							parse("at COORD_EXPR [time EXPRESSION]", 1.0);
							sys(SPECIAL_EFFECT_POSITION);
							return replace(start, "OBJECT");
						} else if (symbol.is("on")) {
							//create special effect CONST_EXPR on OBJECT [time EXPRESSION]
							parse("on OBJECT [time EXPRESSION]", 1.0);
							sys(SPECIAL_EFFECT_OBJECT);
							return replace(start, "OBJECT");
						} else {
							throw new ParseException("Unexpected token: "+symbol+". Expected: at|on", lastParseException, file, symbol.token.line, symbol.token.col);
						}
					}
				} else if (symbol.is("young")) {
					//create young creature from OBJECT with OBJECT knowledge at COORD_EXPR
					parse("young creature from OBJECT with OBJECT knowledge at COORD_EXPR");
					sys(CREATURE_CREATE_YOUNG_WITH_KNOWLEDGE);
					return replace(start, "OBJECT");
				} else if (symbol.is("dumb") || symbol.is("creature")) {
					//create [dumb] creature from creature OBJECT EXPRESSION at COORD_EXPR CONST_EXPR
					boolean dumb = symbol.is("dumb");
					if (dumb) {
						next();
					}
					parse("creature from creature OBJECT EXPRESSION at COORD_EXPR CONST_EXPR");
					pushb(dumb);
					sys(CREATURE_CREATE_RELATIVE_TO_CREATURE);
					return replace(start, "OBJECT");
				} else if (symbol.is("player")) {
					parse("player EXPRESSION");
					symbol = peek();
					if (symbol.is("temple")) {
						//create player EXPRESSION temple at COORD_EXPR
						parse("temple at COORD_EXPR");
						sys(CREATE_PLAYER_TEMPLE);
						return replace(start, "OBJECT");
					} else if (symbol.is("town")) {
						//create player EXPRESSION town at COORD_EXPR type TRIBE_TYPE
						parse("town at COORD_EXPR type CONST_EXPR");
						sys(GAME_CREATE_TOWN);
						return replace(start, "OBJECT");
					} else {
						throw new ParseException("Expected: temple|town", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				} else if (symbol.is("alex")) {
					//create alex special effect CONST_EXPR at COORD_EXPR
					parse("alex special effect CONST_EXPR at COORD_EXPR");
					sys(ALEX_SPECIAL_EFFECT_POSITION);
					return replace(start, "OBJECT");
				} else {
					parseConstExpr(true);
					symbol = peek();
					if (symbol.is("at")) {
						pushi(DEFAULT_SUBTYPE_NAME);
					} else {
						parseConstExpr(true);
					}
					parse("at COORD_EXPR");
					//create SCRIPT_OBJECT_TYPE [SCRIPT_OBJECT_SUBTYPE] at COORD_EXPR
					sys(CREATE);
					return replace(start, "OBJECT");
				}
			} else if (symbol.is("marker")) {
				parse("marker at");
				pushi("SCRIPT_OBJECT_TYPE_MARKER");
				pushi(0);
				symbol = parseCoordExpr(false);
				if (symbol != null) {
					//marker at COORD_EXPR
					sys(CREATE);
					return replace(start, "OBJECT");
				} else {
					symbol = parseConstExpr(false);
					if (symbol != null) {
						//marker at CONST_EXPR
						throw new ParseException("Statement not implemented", lastParseException, file, line, col);
						//return replace(start, "OBJECT");
					} else {
						symbol = peek();
						throw new ParseException("Expected: COORD_EXPR|CONST_EXPR", lastParseException, file, symbol.token.line, symbol.token.col);
					}
				}
			} else if (symbol.is("reward")) {
				parse("reward CONST_EXPR");
				symbol = peek();
				if (symbol.is("at")) {
					parse("at COORD_EXPR [from sky]");
					//reward CONST_EXPR at COORD_EXPR [from sky]
					sys(CREATE_REWARD);
					return replace(start, "OBJECT");
				} else if (symbol.is("in")) {
					parse("in OBJECT at COORD_EXPR [from sky]");
					//reward CONST_EXPR in OBJECT at COORD_EXPR [from sky]
					sys(CREATE_REWARD_IN_TOWN);
					return replace(start, "OBJECT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: at|in", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("flock")) {
				parse("flock at COORD_EXPR");
				//flock at COORD_EXPR
				sys(FLOCK_CREATE);
				return replace(start, "OBJECT");
			} else if (symbol.is("make")) {
				parse("make OBJECT dance CONST_EXPR around COORD_EXPR time EXPRESSION");
				//make OBJECT dance CONST_EXPR around COORD_EXPR time EXPRESSION
				sys(DANCE_CREATE);
				return replace(start, "OBJECT");
			} else if (symbol.is("cast")) {
				parse("cast CONST_EXPR spell");
				symbol = peek();
				if (symbol.is("on")) {
					//cast CONST_EXPR spell on OBJECT from COORD_EXPR radius EXPRESSION time EXPRESSION curl EXPRESSION [without reaction]
					parse("on OBJECT from COORD_EXPR radius EXPRESSION time EXPRESSION curl EXPRESSION");
					symbol = peek();
					if (symbol.is("without")) {
						parse("without reaction");
						pushb(false);
					} else {
						pushb(true);
					}
					sys(SPELL_AT_THING);
					return replace(start, "OBJECT");
				} else if (symbol.is("at")) {
					//cast CONST_EXPR spell at COORD_EXPR from COORD_EXPR radius EXPRESSION time EXPRESSION curl EXPRESSION [without reaction]
					parse("at COORD_EXPR from COORD_EXPR radius EXPRESSION time EXPRESSION curl EXPRESSION");
					symbol = peek();
					if (symbol.is("without")) {
						parse("without reaction");
						pushb(false);
					} else {
						pushb(true);
					}
					sys(SPELL_AT_POS);
					return replace(start, "OBJECT");
				} else {
					throw new ParseException("Unexpected token: "+symbol+". Expected: on|at", lastParseException, file, symbol.token.line, symbol.token.col);
				}
			} else if (symbol.is("attach")) {
				parse("attach OBJECT to OBJECT [as leader]");
				//attach OBJECT to OBJECT [as leader]
				sys(FLOCK_ATTACH);
				return replace(start, "OBJECT");
			} else if (symbol.is("detach")) {
				parse("detach [OBJECT] from OBJECT");
				//detach [OBJECT] from OBJECT
				sys(FLOCK_DETACH);
				return replace(start, "OBJECT");
			} else if (symbol.is(TokenType.IDENTIFIER)) {
				parse("VARIABLE");
				//VARIABLE
				return replace(start, "OBJECT");
			} else if (symbol.is(TokenType.NUMBER) && "0".equals(symbol.token.value)) {
				next();
				//0
				pushf(0f);
				return replace(start, "OBJECT");
			}
		} catch (ParseException e) {
			lastParseException = e;
			if (fail) throw e;
		}
		if (fail) {
			if (lastParseException != null) throw lastParseException;
			symbol = peek();
			throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
		} else {
			revert(start, startIp, startPreserve);
			return null;
		}
	}
	
	private SymbolInstance parseConstExpr(boolean fail) throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		try {
			if (symbol.is("constant")) {
				accept("constant");
				symbol = peek();
				if (symbol.is("from")) {
					parse("from CONST_EXPR to CONST_EXPR");
					//constant from CONST_EXPR to CONST_EXPR
					sys2(RANDOM_ULONG);
					return replace(start, "CONST_EXPR");
				} else {
					parseExpression(fail);
					//constant EXPRESSION
					casti();
					return replace(start, "CONST_EXPR");
				}
			} else if (symbol.is("get")) {
				accept("get");
				symbol = peek();
				if (symbol.is("action")) {
					parse("action text for OBJECT");
					//get action text for OBJECT
					sys(GET_ACTION_TEXT_FOR_OBJECT);
					return replace(start, "CONST_EXPR");
				} else if (symbol.is("hand")) {
					parse("hand state");
					//get hand state
					sys(GET_HAND_STATE);
					return replace(start, "CONST_EXPR");
				} else if (symbol.is("player")) {
					parse("player EXPRESSION last spell cast");
					//get player EXPRESSION last spell cast
					sys(PLAYER_SPELL_LAST_CAST);
					return replace(start, "CONST_EXPR");
				} else {
					symbol = parseObject(false);
					if (symbol != null) {
						symbol = peek();
						if (symbol.is("type")) {
							accept("type");
							//get OBJECT type
							sys(GAME_TYPE);
							return replace(start, "CONST_EXPR");
						} else if (symbol.is("sub")) {
							parse("sub type");
							//get OBJECT sub type
							sys(GAME_SUB_TYPE);
							return replace(start, "CONST_EXPR");
						} else if (symbol.is("leash")) {
							parse("leash type");
							//get OBJECT leash type
							sys(GET_OBJECT_LEASH_TYPE);
							return replace(start, "CONST_EXPR");
						} else if (symbol.is("fight")) {
							parse("fight action");
							//get OBJECT fight action
							sys(GET_CREATURE_FIGHT_ACTION);
							return replace(start, "CONST_EXPR");
						} else if (symbol.is("current")) {
							parse("current action");
							//get OBJECT current action
							sys(GET_CREATURE_CURRENT_ACTION);
							return replace(start, "EXPRESSION");
						}
					} else {
						symbol = parseConstExpr(false);
						if (symbol != null) {
							parse("opposite creature type");
							//get CONST_EXPR opposite creature type
							sys(OPPOSING_CREATURE);
							return replace(start, "CONST_EXPR");
						} else if (fail) {
							symbol = peek();
							throw new ParseException("Expected: OBJECT|CONST_EXPR", lastParseException, file, symbol.token.line, symbol.token.col);
						}
					}
				}
			} else if (symbol.is("state")) {
				parse("state of OBJECT");
				//state of OBJECT
				sys(GET_OBJECT_STATE);
				return replace(start, "CONST_EXPR");
			} else if (symbol.is("(")) {
				parse("( CONST_EXPR )");
				//(CONST_EXPR)
				return replace(start, "CONST_EXPR");
			} else if (symbol.is(TokenType.NUMBER) || symbol.is(TokenType.IDENTIFIER)) {
				parse("CONSTANT");
				//CONSTANT
				return replace(start, "CONST_EXPR");
			}
		} catch (ParseException e) {
			if (fail) throw e;
		}
		if (fail) {
			symbol = peek();
			throw new ParseException("Unexpected token: "+symbol+". Expected CONST_EXPR", file, symbol.token.line, symbol.token.col);
		} else {
			seek(start);
			return null;
		}
	}
	
	private SymbolInstance parseCoordExpr(boolean fail) throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		SymbolInstance newSym = parseCoordExpr1();
		while (newSym != null && newSym != symbol) {
			symbol = newSym;
			prev();
			newSym = parseCoordExpr1();
		}
		symbol = peek();
		if ("COORD_EXPR".equals(symbol.symbol.keyword)) {
			next();
			return symbol;
		}
		if (fail) {
			if (lastParseException != null) throw lastParseException;
			symbol = peek();
			throw new ParseException("Unexpected token: "+symbol+". Expected COORD_EXPR", file, symbol.token.line, symbol.token.col);
		} else {
			seek(start);
			return null;
		}
	}
	
	private SymbolInstance parseCoordExpr1() throws ParseException {
		final int start = it.nextIndex();
		SymbolInstance symbol = peek();
		if ("COORD_EXPR".equals(symbol.symbol.keyword)) {
			next();
			symbol = next();
			if (symbol.is("/")) {
				parseExpression(true);
				//COORD_EXPR / EXPRESSION
				throw new ParseException("Statement not implemented", lastParseException, file, line, col);
				//return replace(start, "COORD_EXPR");
			} else if (symbol.is("+") || symbol.is("-")) {
				//parseCoordExpr(true);
				parseCoordExpr1();
				if (symbol.is("+")) {
					//COORD_EXPR + COORD_EXPR
					addc();
				} else if (symbol.is("-")) {
					//COORD_EXPR - COORD_EXPR
					subc();
				}
				return replace(start, "COORD_EXPR");
			} else {
				seek(start);
				return peek();
			}
		} else if (symbol.is("[")) {
			accept("[");
			final int checkpoint = it.nextIndex();
			final int checkpointIp = getIp();
			final SymbolInstance checkpointSymbol = peek();
			symbol = parseObject(false);
			try {
				accept("]");
				sys(GET_POSITION);
				//[OBJECT]
				return replace(start, "COORD_EXPR");
			} catch (ParseException e) {
				lastParseException = e;
			}
			revert(checkpoint, checkpointIp, checkpointSymbol);
			parseExpression(true);
			try {
				castc();
				parse(", EXPRESSION");
				castc();
				symbol = peek();
				if (symbol.is(",")) {
					parse(", EXPRESSION");
					castc();
					accept("]");
					//[EXPRESSION, EXPRESSION, EXPRESSION]
					return replace(start, "COORD_EXPR");
				} else if (symbol.is("]")) {
					accept("]");
					pushc(0);
					swap();
					//[EXPRESSION, EXPRESSION]
					return replace(start, "COORD_EXPR");
				} else {
					throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
				}
			} catch (ParseException e) {
				lastParseException = e;
			}
			revert(checkpoint, checkpointIp, checkpointSymbol);
		} else if (symbol.is("camera")) {
			accept("camera");
			symbol = peek();
			if (symbol.is("position")) {
				accept("position");
				//camera position
				sys(GET_CAMERA_POSITION);
				return replace(start, "COORD_EXPR");
			} else if (symbol.is("focus")) {
				accept("focus");
				//camera focus
				sys(GET_CAMERA_FOCUS);
				return replace(start, "COORD_EXPR");
			} else {
				//camera CONST_EXPR
				symbol = accept(TokenType.IDENTIFIER);
				String camEnum = challengeName + symbol.token.value;
				int constVal = getConstant(camEnum);
				pushi(constVal);
				sys(CONVERT_CAMERA_FOCUS);
				return replace(start, "COORD_EXPR");
			}
		} else if (symbol.is("stored")) {
			parse("stored camera");
			symbol = peek();
			if (symbol.is("position")) {
				accept("position");
				//stored camera position
				sys(GET_STORED_CAMERA_POSITION);
				return replace(start, "COORD_EXPR");
			} else if (symbol.is("focus")) {
				accept("focus");
				//stored camera focus
				sys(GET_STORED_CAMERA_FOCUS);
				return replace(start, "COORD_EXPR");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("hand")) {
			parse("hand position");
			//hand position
			sys(GET_HAND_POSITION);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("facing")) {
			parse("facing camera position distance EXPRESSION");
			//facing camera position distance EXPRESSION
			sys(GET_FACING_CAMERA_POSITION);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("computer")) {
			parse("computer player EXPRESSION position");
			//computer player EXPRESSION position
			sys(GET_COMPUTER_PLAYER_POSITION);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("last")) {
			parse("last player EXPRESSION spell cast position");
			//last player EXPRESSION spell cast position
			sys(GET_LAST_SPELL_CAST_POS);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("get")) {
			parse("get target from COORD_EXPR to COORD_EXPR distance EXPRESSION angle EXPRESSION");
			//get target from COORD_EXPR to COORD_EXPR distance EXPRESSION angle EXPRESSION
			sys(GET_TARGET_RELATIVE_POS);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("arse")) {
			parse("arse position of OBJECT");
			//arse position of OBJECT
			sys(GET_ARSE_POSITION);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("belly")) {
			parse("belly position of OBJECT");
			//belly position of OBJECT
			sys(GET_BELLY_POSITION);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("destination")) {
			parse("destination of OBJECT");
			//destination of OBJECT
			sys(GET_OBJECT_DESTINATION);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("player")) {
			parse("player EXPRESSION temple");
			symbol = peek();
			if (symbol.is("position")) {
				accept("position");
				//player EXPRESSION temple position
				sys(GET_TEMPLE_POSITION);
				return replace(start, "COORD_EXPR");
			} else if (symbol.is("entrance")) {
				parse("entrance position radius EXPRESSION height EXPRESSION");
				//player EXPRESSION temple entrance position radius EXPRESSION height EXPRESSION
				sys(GET_TEMPLE_ENTRANCE_POSITION);
				return replace(start, "COORD_EXPR");
			} else {
				throw new ParseException("Unexpected token: "+symbol, file, symbol.token.line, symbol.token.col);
			}
		} else if (symbol.is("landing")) {
			parse("landing position of OBJECT velocity heading COORD_EXPR speed EXPRESSION");
			//landing position of OBJECT velocity heading COORD_EXPR speed EXPRESSION
			sys(GET_LANDING_POS);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("extra")) {
			parse("extra position EXPRESSION of OBJECT");
			//extra position EXPRESSION of OBJECT
			sys(GET_OBJECT_EP);
			return replace(start, "COORD_EXPR");
		} else if (symbol.is("-")) {
			parse("- COORD_EXPR");
			//-COORD_EXPR
			throw new ParseException("Statement not implemented", lastParseException, file, line, col);
			//return replace(start, "COORD_EXPR");
		} else if (symbol.is("(")) {
			parse("( COORD_EXPR )");
			//(COORD_EXPR)
			return replace(start, "COORD_EXPR");
		} else {
			final int checkpoint = it.nextIndex();
			final int checkpointIp = getIp();
			final SymbolInstance checkpointPreserve = peek();
			symbol = parseExpression(false);
			if (symbol != null) {
				SymbolInstance mode = next();
				if (mode.is("*")) {
					parseCoordExpr(true);
					//EXPRESSION * COORD_EXPR
					throw new ParseException("Statement not implemented", lastParseException, file, line, col);
					//return replace(start, "COORD_EXPR");
				}
				revert(checkpoint, checkpointIp, checkpointPreserve);
			}
		}
		seek(start);
		return null;
	}
	
	private SymbolInstance parseOptionKeyword(String wordTrue, String wordFalse) throws ParseException {
		SymbolInstance symbol = next();
		if (symbol.is(wordTrue)) {
			pushb(true);
		} else if (symbol.is(wordFalse)) {
			pushb(false);
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: "+wordTrue+"|"+wordFalse, lastParseException, file, symbol.token.line, symbol.token.col);
		}
		return symbol;
	}
	
	private SymbolInstance parseSpiritType() throws ParseException {
		SymbolInstance symbol = next();
		if (symbol.is("good")) {
			pushi(1);
		} else if (symbol.is("evil")) {
			pushi(2);
		} else if (symbol.is("last")) {
			pushi(3);
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: good|evil|last", lastParseException, file, symbol.token.line, symbol.token.col);
		}
		return symbol;
	}
	
	private SymbolInstance parsePlayingSide() throws ParseException {
		SymbolInstance symbol = next();
		if (symbol.is("away")) {
			throw new ParseException("PLAYING_SIDE not implemented", lastParseException, file, line, col);
		} else {
			throw new ParseException("Unexpected token: "+symbol+". Expected: away", lastParseException, file, symbol.token.line, symbol.token.col);
		}
		//return symbol;
	}
	
	/**If the given symbol is a number then returns its value; if the symbol is an identifier then
	 * returns the value of the constant with the given name.
	 * @param symbol
	 * @return
	 * @throws ParseException
	 */
	private int getConstant(SymbolInstance symbol) throws ParseException {
		if (symbol.is(TokenType.IDENTIFIER)) {
			String name = symbol.token.value;
			return getConstant(name);
		} else if (symbol.is(TokenType.NUMBER)) {
			return symbol.token.intVal();
		} else {
			throw new ParseException(symbol + " is not a valid constant", lastParseException, file, symbol.token.line, symbol.token.col);
		}
	}
	
	/**Returns the value of the given constant.
	 * @param name
	 * @return
	 * @throws ParseException
	 */
	private int getConstant(String name) throws ParseException {
		Integer val = localConst.get(name);
		if (val == null) {
			val = constants.get(name);
		}
		if (val == null) {
			lastParseException = new ParseException("Undefined constant: "+name, file, line, col);
			throw lastParseException;
		} else {
			lastParseException = null;
		}
		return val;
	}
	
	/**Returns the ID of the given variable.
	 * @param name
	 * @return
	 * @throws ParseException
	 */
	private int getVarId(String name) throws ParseException {
		return getVarId(name, 0);
	}
	
	/**Returns the ID of the given variable.
	 * @param name
	 * @param index
	 * @return
	 * @throws ParseException
	 */
	private int getVarId(String name, int index) throws ParseException {
		Var var = localMap.get(name);
		if (var == null) {
			var = globalMap.get(name);
		}
		if (var == null) {
			if (!name.equals(name.toUpperCase()) || externalVars.contains(name)) {
				return -objcode.getExternalVarId(name, index);
			}
			throw new ParseException("Undefined variable: "+name, file, line, col);
		} else {
			lastParseException = null;
		}
		if (index < 0 || (index >= var.size && !var.ref)) {
			if (options.staticArrayCheck) {
				throw new ParseError("Index "+index+" out of bounds for "+name+"["+var.size+"]", file, line, col);
			} else {
				warning("WARNING: Index "+index+" out of bounds for "+name+"["+var.size+"] in "+file.getName()+":"+line+":"+col);
			}
		}
		return var.index + index;
	}
	
	private SymbolInstance next() {
		return next(true);
	}
	
	private SymbolInstance next(boolean skipEol) {
		SymbolInstance r = it.next();
		if (skipEol) {
			while (r.is(TokenType.EOL)) {
				r = it.next();
			}
		}
		if (r.token != null) {
			line = r.token.line;
			col = r.token.col;
		}
		return r;
	}
	
	private SymbolInstance prev() {
		SymbolInstance r = it.previous();
		if (r.token != null) {
			line = r.token.line;
			col = r.token.col;
		}
		return r;
	}
	
	private boolean checkAhead(String expression) {
		final int start = it.nextIndex();
		String[] symbols = expression.split(" ");
		boolean match = true;
		for (String symbol : symbols) {
			if ("ANY".equals(symbol)) {
				next();
			} else if ("IDENTIFIER".equals(symbol) || "NUMBER".equals(symbol) || "STRING".equals(symbol)) {
				SymbolInstance sInst = next();
				TokenType type = TokenType.valueOf(symbol);
				if (!sInst.is(type)) {
					match = false;
					break;
				}
			} else if ("CONSTANT".equals(symbol)) {
				SymbolInstance sInst = next();
				if (!sInst.is(TokenType.IDENTIFIER) && !sInst.is(TokenType.NUMBER)) {
					match = false;
					break;
				}
			} else if ("EOL".equals(symbol)) {
				SymbolInstance sInst = next(false);
				if (!sInst.is(TokenType.EOL)) {
					match = false;
					break;
				}
			} else {
				SymbolInstance sInst = next();
				if (!sInst.is(symbol)) {
					match = false;
					break;
				}
			}
		}
		while (it.nextIndex() > start) {
			prev();
		}
		return match;
	}
	
	private SymbolInstance peek() {
		return peek(true);
	}
	
	private SymbolInstance peek(boolean skipEol) {
		final int start = it.nextIndex();
		SymbolInstance r = next(skipEol);
		while (it.nextIndex() > start) {
			prev();
		}
		return r;
	}
	
	private SymbolInstance peek(int forward) {
		final int start = it.nextIndex();
		if (forward < 0) {
			throw new IllegalArgumentException("Invalid peek offset: "+forward);
		}
		for (int i = 0; i < forward; i++) {
			next();
		}
		SymbolInstance r = next();
		while (it.nextIndex() > start) {
			prev();
		}
		return r;
	}
	
	private void seek(final int index) {
		while (it.nextIndex() < index) {
			it.next();
		}
		while (it.nextIndex() > index) {
			prev();
		}
		peek();
	}
	
	private int storeStringData(String value) throws ParseError {
		int strptr = strings.getOrDefault(value, -1);
		if (!options.sharedStrings || strptr < 0) {
			byte[] data = value.getBytes(ASCII);
			strptr = dataBuffer.size();
			strings.put(value, strptr);
			try {
				dataBuffer.write(data);
			} catch (IOException e) {
				throw new ParseError(e, file, line);
			}
			dataBuffer.write((byte)0);
		}
		return strptr;
	}
	
	private SymbolInstance parseString() throws ParseException {
		SymbolInstance sInst = next();
		if (!sInst.is(TokenType.STRING)) {
			throw new ParseException("Unexpected token: "+sInst+". Expected: STRING", lastParseException, file, sInst.token.line, sInst.token.col);
		}
		String value = sInst.token.stringVal();
		int strptr = storeStringData(value);
		//STRING
		objcode.getStringInstructions().add(getIp());
		pushi(strptr);
		return sInst;
	}
	
	private SymbolInstance[] parse(String expression, Object... defaults) throws ParseException {
		String[] symbols = expression.split(" ");
		SymbolInstance[] r = new SymbolInstance[symbols.length];
		int defaultIndex = 0;
		for (int i = 0; i < symbols.length; i++) {
			String symbol = symbols[i];
			if ("EXPRESSION".equals(symbol)) {
				r[i] = parseExpression(true);
			} else if ("COORD_EXPR".equals(symbol)) {
				r[i] = parseCoordExpr(true);
			} else if ("CONST_EXPR".equals(symbol)) {
				r[i] = parseConstExpr(true);
			} else if ("OBJECT".equals(symbol)) {
				r[i] = parseObject(true);
			} else if ("CONDITION".equals(symbol)) {
				r[i] = parseCondition(true);
			} else if ("STATEMENT".equals(symbol)) {
				r[i] = parseStatement();
			} else if ("IDENTIFIER".equals(symbol)) {
				r[i] = accept(TokenType.IDENTIFIER);
			} else if ("VARIABLE".equals(symbol)) {
				r[i] = accept(TokenType.IDENTIFIER);
				String name = r[i].token.value;
				Var var = localMap.get(name);
				boolean ref = var != null && var.ref;
				SymbolInstance sInst = peek();
				if (sInst.is("[")) {
					accept("[");
					if (checkAhead("NUMBER ]")) {
						//IDENTIFIER\[NUMBER\]
						sInst = next();
						accept("]");
						int index = sInst.token.intVal();
						if (ref) {
							pushvVal(name);
							pushf(index);
							ref_add_pushv();
						} else {
							pushvVal(name, index);
						}
					} else {
						//IDENTIFIER\[EXPRESSION\]
						parseExpression(true);
						accept("]");
						if (ref) {
							pushvVal(name);
							ref_add_pushf();
						} else {
							int varId = getVarId(name);
							pushf(varId);
							addf();
							ref_push2();
						}
					}
				} else {
					pushvVal(name);
				}
			} else if ("CONSTANT".equals(symbol)) {
				r[i] = acceptAny(TokenType.NUMBER, TokenType.IDENTIFIER);
				int val = getConstant(r[i]);
				pushi(val);
			} else if ("STRING".equals(symbol)) {
				r[i] = parseString();
			} else if ("EOL".equals(symbol)) {
				if (dontRequireEol) {
					r[i] = null;
				} else {
					SymbolInstance sInst = next(false);
					if (!sInst.is(TokenType.EOL)) {
						lastParseException = new ParseException("Unexpected token: "+sInst+". Expected: EOL", lastParseException, file, sInst.token.line, sInst.token.col);
						throw lastParseException;
					}
					r[i] = sInst;
				}
			} else if ("SPIRIT_TYPE".equals(symbol)) {
				r[i] = parseSpiritType();
			} else if ("PLAYING_SIDE".equals(symbol)) {
				r[i] = parsePlayingSide();
			} else if (symbol.indexOf("|") >= 0) {
				if ("enable|disable".equals(symbol)) {
					r[i] = parseOptionKeyword("enable", "disable");
				} else if ("forward|reverse".equals(symbol)) {
					r[i] = parseOptionKeyword("forward", "reverse");
				} else if ("open|close".equals(symbol)) {
					r[i] = parseOptionKeyword("open", "close");
				} else if ("pause|unpause".equals(symbol)) {
					r[i] = parseOptionKeyword("pause", "unpause");
				} else if ("quest|challenge".equals(symbol)) {
					r[i] = parseOptionKeyword("quest", "challenge");
				} else if ("enter|exit".equals(symbol)) {
					r[i] = parseOptionKeyword("enter", "exit");
				} else if ("left|right".equals(symbol)) {
					r[i] = parseOptionKeyword("left", "right");
				} else if ("up|down".equals(symbol)) {
					r[i] = parseOptionKeyword("up", "down");
				} else if ("second|seconds".equals(symbol)) {
					SymbolInstance sInst = next();
					if (!sInst.is("second") && !sInst.is("seconds")) {
						lastParseException = new ParseException("Unexpected token: "+sInst+". Expected: second|seconds", lastParseException, file, sInst.token.line, sInst.token.col);
						throw lastParseException;
					}
					r[i] = sInst;
				} else if ("event|events".equals(symbol)) {
					SymbolInstance sInst = next();
					if (!sInst.is("event") && !sInst.is("events")) {
						lastParseException = new ParseException("Unexpected token: "+sInst+". Expected: event|events", lastParseException, file, sInst.token.line, sInst.token.col);
						throw lastParseException;
					}
					r[i] = sInst;
				} else if ("graphics|gfx".equals(symbol)) {
					SymbolInstance sInst = next();
					if (!sInst.is("graphics") && !sInst.is("gfx")) {
						lastParseException = new ParseException("Unexpected token: "+sInst+". Expected: graphics|gfx", lastParseException, file, sInst.token.line, sInst.token.col);
						throw lastParseException;
					}
					r[i] = sInst;
				} else {
					throw new IllegalArgumentException("Unknown symbol: "+symbol);
				}
			} else if (symbol.startsWith("[") && !"[".equals(symbol)) {	//Optional expression
				boolean ended = false;
				boolean match = true;
				boolean flag = true;	//Tells if this optional expression must generate a boolean value (the other case is a default value)
				symbols[i] = symbols[i].substring(1);
				for (int start = i; i < symbols.length && !ended; i++) {
					String expr = symbols[i];
					if (expr.endsWith("]")) {
						expr = expr.substring(0, expr.length() - 1);
						ended = true;
					}
					if ("EXPRESSION".equals(expr)) {
						flag = false;
						if (match) {
							r[i] = parseExpression(true);
						} else {
							float def = defaultIndex < defaults.length ? asFloat(defaults[defaultIndex]) : 0;
							pushf(def);
						}
						defaultIndex++;
					} else if ("CONST_EXPR".equals(expr)) {
						flag = false;
						if (match) {
							r[i] = parseConstExpr(true);
						} else {
							int def = defaultIndex < defaults.length ? asInt(defaults[defaultIndex]) : 0;
							pushi(def);
						}
						defaultIndex++;
					} else if ("COORD_EXPR".equals(expr)) {
						flag = false;
						if (match) {
							r[i] = parseCoordExpr(true);
							pushb(true);	//with position
						} else {
							pushc(0);
							pushc(0);
							pushc(0);
							pushb(false);	//without position
						}
						defaultIndex++;
					} else if ("OBJECT".equals(expr)) {
						flag = false;
						if (match) {
							r[i] = parseObject(false);
							if (r[i] == null) {
								pusho(0);
							}
						} else {
							pusho(0);
						}
						defaultIndex++;
					} else if (match) {
						SymbolInstance sInst = peek(false);
						if ("EOL".equals(expr) && sInst.is(TokenType.EOL)) {
							accept(TokenType.EOL);
						} else if (sInst.is(expr)) {
							accept(expr);
						} else if (i > start) {
							lastParseException = new ParseException("Unexpected token: "+sInst+". Expected: "+expr, lastParseException, file, sInst.token.line, sInst.token.col);
							throw lastParseException;
						} else {
							match = false;
						}
					}
				}
				i--;
				if (flag) {
					pushb(match);
				}
			} else {
				SymbolInstance sInst = next();
				if (!sInst.is(symbol)) {
					lastParseException = new ParseException("Unexpected token: "+sInst+". Expected: "+symbol, lastParseException, file, sInst.token.line, sInst.token.col);
					throw lastParseException;
				}
				r[i] = sInst;
			}
		}
		lastParseException = null;
		return r;
	}
	
	private SymbolInstance accept(String keyword) throws ParseException {
		SymbolInstance symbol = next();
		if (!symbol.is(keyword)) {
			throw new ParseException("Unexpected token: "+symbol+". Expected: "+keyword, file, symbol.token.line, symbol.token.col);
		}
		return symbol;
	}
	
	private SymbolInstance acceptAny(TokenType...types) throws ParseException {
		SymbolInstance symbol = next();
		for (TokenType type : types) {
			if (symbol.token.type == type) {
				return symbol;
			}
		}
		throw new ParseException("Unexpected token: "+symbol+". Expected: "+join("|", types), file, symbol.token.line, symbol.token.col);
	}
	
	private SymbolInstance accept(TokenType type) throws ParseException {
		if (type == TokenType.EOL && dontRequireEol) {
			return null;
		} else {
			SymbolInstance symbol = next(type != TokenType.EOL);
			if (symbol.token.type != type) {
				throw new ParseException("Unexpected token: "+symbol+". Expected: "+type, file, symbol.token.line, symbol.token.col);
			}
			return symbol;
		}
	}
	
	private SymbolInstance replace(final int index, String symbol) {
		SymbolInstance newInst = new SymbolInstance(Syntax.getSymbol(symbol));
		while (it.nextIndex() > index) {
			SymbolInstance sInst = prev();
			it.remove();
			newInst.expression.add(0, sInst);
		}
		it.add(newInst);
		return newInst;
	}
	
	private void revert(final int index, final int instructionAddress, SymbolInstance preserve) {
		while (it.nextIndex() > index) {
			SymbolInstance sInst = prev();
			if (sInst != preserve && sInst.expression != null) {
				it.remove();
				for (SymbolInstance sym : sInst.expression) {
					it.add(sym);
				}
			}
		}
		while (instructions.size() > instructionAddress) {
			instructions.remove(instructions.size() - 1);
		}
	}
	
	private SymbolInstance toSymbol(int pos, Token token) throws ParseException {
		SymbolInstance sInst = null;
		switch (token.type) {
			case EOL:
				sInst = new SymbolInstance(Syntax.EOL, token);
				break;
			case IDENTIFIER:
				sInst = new SymbolInstance(Syntax.IDENTIFIER, token);
				break;
			case NUMBER:
				sInst = new SymbolInstance(Syntax.NUMBER, token);
				break;
			case STRING:
				sInst = new SymbolInstance(Syntax.STRING, token);
				break;
			case KEYWORD:
				sInst = new SymbolInstance(Syntax.getSymbol(token.value), token);
				break;
			case ANNOTATION:
				sInst = new SymbolInstance(Syntax.ANNOTATION, token);
				break;
			default:
				throw new ParseException("Unrecognized symbol: "+token.value, file, token.line, token.col);
		}
		return sInst;
	}
	
	private void end() {
		Instruction instruction = Instruction.fromKeyword("END");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void jz(int dstIp) {
		int ip = getIp();
		Instruction instruction = Instruction.fromKeyword("JZ");
		if (dstIp > ip) instruction.mode = OPCodeMode.FORWARD;
		instruction.intVal = dstIp;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private Instruction jz() {
		Instruction instruction = Instruction.fromKeyword("JZ");
		instruction.mode = OPCodeMode.FORWARD;
		instruction.lineNumber = line;
		instructions.add(instruction);
		return instruction;
	}
	
	private void pushi(int val) {
		Instruction instruction = Instruction.fromKeyword("PUSHI");
		instruction.intVal = val;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pushf(float val) {
		Instruction instruction = Instruction.fromKeyword("PUSHF");
		instruction.floatVal = val;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pushc(float val) {
		Instruction instruction = Instruction.fromKeyword("PUSHC");
		instruction.floatVal = val;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pusho(int val) {
		Instruction instruction = Instruction.fromKeyword("PUSHO");
		instruction.intVal = val;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pushb(boolean val) {
		Instruction instruction = Instruction.fromKeyword("PUSHB");
		instruction.boolVal = val;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pushi(String constant) throws ParseException {
		Instruction instruction = Instruction.fromKeyword("PUSHI");
		instruction.intVal = getConstant(constant);
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pushvAddr(String variable) throws ParseException {
		pushvAddr(variable, 0);
	}
	
	private void pushvAddr(String variable, int index) throws ParseException {
		Instruction instruction = Instruction.fromKeyword("PUSHV");
		instruction.intVal = getVarId(variable, index);
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void pushvVal(String variable) throws ParseException {
		pushvVal(variable, 0);
	}
	
	private void pushvVal(String variable, int index) throws ParseException {
		Instruction instruction = Instruction.fromKeyword("PUSHV");
		instruction.mode = OPCodeMode.REF;
		instruction.intVal = getVarId(variable, index);
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void popi() {
		Instruction instruction = Instruction.fromKeyword("POPI");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void popf() {
		Instruction instruction = Instruction.fromKeyword("POPF");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void popo() {
		Instruction instruction = Instruction.fromKeyword("POPO");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void popf(String variable) throws ParseException {
		Instruction instruction = Instruction.fromKeyword("POPF");
		instruction.mode = OPCodeMode.REF;
		instruction.intVal = getVarId(variable);
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void addf() {
		Instruction instruction = Instruction.fromKeyword("ADDF");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void addc() {
		Instruction instruction = Instruction.fromKeyword("ADDC");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void checkContext(NativeFunction func) {
		if (func.context == null) {
			return;
		}
		switch (func.context) {
			case CAMERA:
				if (!inCameraBlock) {
					throw new ParseError("Function "+func+" must be called within a camera/cinema block", file, line, col);
				}
				break;
			case DIALOGUE:
				if (!inDialogueBlock) {
					throw new ParseError("Function "+func+" must be called within a dialogue/cinema block", file, line, col);
				}
				break;
			default:
		}
	}
	
	private void sys(NativeFunction func) {
		checkContext(func);
		Instruction instruction = Instruction.fromKeyword("SYS");
		instruction.intVal = func.ordinal();
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void sys2(NativeFunction func) {
		checkContext(func);
		Instruction instruction = Instruction.fromKeyword("SYS2");
		instruction.intVal = func.ordinal();
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void subf() {
		Instruction instruction = Instruction.fromKeyword("SUBF");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void subc() {
		Instruction instruction = Instruction.fromKeyword("SUBC");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void neg() {
		Instruction instruction = Instruction.fromKeyword("NEG");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void mul() {
		Instruction instruction = Instruction.fromKeyword("MUL");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void div() {
		Instruction instruction = Instruction.fromKeyword("DIV");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void mod() {
		Instruction instruction = Instruction.fromKeyword("MOD");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void not() {
		Instruction instruction = Instruction.fromKeyword("NOT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void and() {
		Instruction instruction = Instruction.fromKeyword("AND");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void or() {
		Instruction instruction = Instruction.fromKeyword("OR");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void eq() {
		Instruction instruction = Instruction.fromKeyword("EQ");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void neq() {
		Instruction instruction = Instruction.fromKeyword("NEQ");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void geq() {
		Instruction instruction = Instruction.fromKeyword("GEQ");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void leq() {
		Instruction instruction = Instruction.fromKeyword("LEQ");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void gt() {
		Instruction instruction = Instruction.fromKeyword("GT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void lt() {
		Instruction instruction = Instruction.fromKeyword("LT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private Instruction jmp(int dstIp) {
		int ip = getIp();
		Instruction instruction = Instruction.fromKeyword("JMP");
		if (dstIp > ip) instruction.mode = OPCodeMode.FORWARD;
		instruction.intVal = dstIp;
		instruction.lineNumber = line;
		instructions.add(instruction);
		return instruction;
	}
	
	private Instruction jmp() {
		Instruction instruction = Instruction.fromKeyword("JMP");
		instruction.mode = OPCodeMode.FORWARD;
		instruction.lineNumber = line;
		instructions.add(instruction);
		return instruction;
	}
	
	private void sleep() {
		Instruction instruction = Instruction.fromKeyword("SLEEP");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private Instruction except() {
		Instruction instruction = Instruction.fromKeyword("EXCEPT");
		instruction.lineNumber = line;
		instructions.add(instruction);
		return instruction;
	}
	
	private void casti() {
		Instruction instruction = Instruction.fromKeyword("CASTI");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void castf() {
		Instruction instruction = Instruction.fromKeyword("CASTF");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void castc() {
		Instruction instruction = Instruction.fromKeyword("CASTC");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void casto() {
		Instruction instruction = Instruction.fromKeyword("CASTO");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void castb() {
		Instruction instruction = Instruction.fromKeyword("CASTB");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void call(String scriptname, int argc) {
		Instruction instruction = Instruction.fromKeyword("CALL");
		instruction.lineNumber = line;
		instructions.add(instruction);
		ScriptToResolve call = new ScriptToResolve(file, line, instruction, scriptname, argc);
		calls.add(call);
	}
	
	private void start(String scriptname, int argc) {
		Instruction instruction = Instruction.fromKeyword("START");
		instruction.lineNumber = line;
		instructions.add(instruction);
		ScriptToResolve call = new ScriptToResolve(file, line, instruction, scriptname, argc);
		calls.add(call);
	}
	
	private void endexcept() {
		Instruction instruction = Instruction.fromKeyword("ENDEXCEPT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void free() {
		Instruction instruction = Instruction.fromKeyword("FREE");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void iterexcept() {
		Instruction instruction = Instruction.fromKeyword("ITEREXCEPT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void brkexcept() {
		Instruction instruction = Instruction.fromKeyword("BRKEXCEPT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void swap() {
		swap(0);
	}
	
	private void swap(int offset) {
		Instruction instruction = Instruction.fromKeyword("SWAP");
		instruction.intVal = offset;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void swapf(int offset) {
		Instruction instruction = Instruction.fromKeyword("SWAPF");
		instruction.intVal = offset;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void dup(int offset) {
		Instruction instruction = Instruction.fromKeyword("DUP");
		instruction.intVal = offset;
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_and_offset_push() {
		Instruction instruction = Instruction.fromKeyword("REF_AND_OFFSET_PUSH");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_and_offset_pop() {
		Instruction instruction = Instruction.fromKeyword("REF_AND_OFFSET_POP");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_push() {
		Instruction instruction = Instruction.fromKeyword("REF_PUSH");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_push2() {
		Instruction instruction = Instruction.fromKeyword("REF_PUSH2");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_add_pushf() {
		Instruction instruction = Instruction.fromKeyword("REF_ADD_PUSHF");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_add_pushv() {
		Instruction instruction = Instruction.fromKeyword("REF_ADD_PUSHV");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void ref_add_pushv2() {
		Instruction instruction = Instruction.fromKeyword("REF_ADD_PUSHV2");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void sqrt() {
		Instruction instruction = Instruction.fromKeyword("SQRT");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void tan() {
		Instruction instruction = Instruction.fromKeyword("TAN");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void sin() {
		Instruction instruction = Instruction.fromKeyword("SIN");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void cos() {
		Instruction instruction = Instruction.fromKeyword("COS");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void atan() {
		Instruction instruction = Instruction.fromKeyword("ATAN");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void asin() {
		Instruction instruction = Instruction.fromKeyword("ASIN");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void acos() {
		Instruction instruction = Instruction.fromKeyword("ACOS");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void atan2() {
		Instruction instruction = Instruction.fromKeyword("ATAN2");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private void abs() {
		Instruction instruction = Instruction.fromKeyword("ABS");
		instruction.lineNumber = line;
		instructions.add(instruction);
	}
	
	private int getIp() {
		return instructions.size();
	}
	
	
	private static class ScriptToResolve {
		public final File file;
		public final int line;
		public final Instruction instr;
		public final String name;
		public final int argc;
		
		public ScriptToResolve(File file, int line, Instruction instr, String name, int argc) {
			this.file = file;
			this.line = line;
			this.instr = instr;
			this.name = name;
			this.argc = argc;
		}
	}
	
	
	public static class Options {
		public boolean sharedStrings = true;
		public boolean staticArrayCheck = true;
		public boolean extendedSyntax = false;
		public boolean returnEnabled = false;
		public boolean debug = false;
		public boolean verbose = false;
	}
}
