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
package it.ld.bw.chl.lang.decompiler;

import static it.ld.bw.chl.lang.Utils.*;

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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
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
import it.ld.bw.chl.lang.CHeaderParser;
import it.ld.bw.chl.lang.Symbol;
import it.ld.bw.chl.lang.Syntax;
import it.ld.bw.chl.lang.Type;
import it.ld.bw.chl.lang.Utils;
import it.ld.bw.chl.lang.Var;
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
import it.ld.bw.chl.model.OPCodeMode;
import it.ld.bw.chl.model.Script;

public class CHLDecompiler {
	public static boolean traceEnabled = false;
	
	public static final int HEURISTIC_MIN = 0;
	public static final int HEURISTIC_MAX = 3;
	public static final int HEURISTIC_DFLT = 2;
	
	private static final int RESOLVE_TYPES_MAX_STEPS = 20;
	
	private static final Charset ASCII = Charset.forName("windows-1252");
	
	private static final String STATEMENTS_FILE = "statements.txt";
	
	private static final Expression END_SCRIPT = new Expression("end script");
	private static final Expression SELF_ASSIGN = new Expression("?");
	
	private static final ArgType[] typeMap = new ArgType[] {
			ArgType.UNKNOWN,
			ArgType.INT,
			ArgType.FLOAT,
			ArgType.COORD,
			ArgType.OBJECT,
			ArgType.UNKNOWN,
			ArgType.BOOL,
			ArgType.UNKNOWN
		};
	
	private static final Priority[] priorityMap = new Priority[] {
			Priority.VOID,			//UNKNOWN
			Priority.CONST_EXPR,	//INT
			Priority.EXPRESSION,	//FLOAT
			Priority.COORD_EXPR,	//COORD
			Priority.CONDITION,		//BOOL
			Priority.OBJECT,		//OBJECT
			Priority.EXPRESSION,	//INT_OR_FLOAT	TODO we could do better
			Priority.ATOMIC,		//STRPTR
			Priority.ATOMIC,		//SCRIPT
		};
	
	private static final Symbol[] statements = new Symbol[NativeFunction.values().length];
	private static final Map<String, String[]> boolOptions = new HashMap<>();
	private static final Map<String, String[]> enumOptions = new HashMap<>();
	private static final Map<String, String> dummyOptions = new HashMap<>();
	private static final Map<String, String> coalesceTypes = new HashMap<>();
	
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
		addBoolOption("up", "down");
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
		addBoolOption("dumb");
		//
		addBoolInvOption("without reaction");
		//
		addEnumOption("HELP_SPIRIT_TYPE", "none", "good", "evil", "last");
		addEnumOption("SAY_MODE", null, "with interaction", "without interaction");
		addEnumOption("[anti]", null, "anti");
		addEnumOption("DELETE_MODE", null, "with fade", "with explosion", "with temple explosion");
		//
		dummyOptions.put("second|seconds", "seconds");
		dummyOptions.put("event|events", "events");
		dummyOptions.put("graphics|gfx", "graphics");
		//
		coalesceTypes.put("SCRIPT_OBJECT_TYPE_FEMALE_CREATURE", "SCRIPT_OBJECT_TYPE_CREATURE");
		coalesceTypes.put("SCRIPT_OBJECT_TYPE_DUMB_CREATURE", "SCRIPT_OBJECT_TYPE_CREATURE");
		coalesceTypes.put("SCRIPT_OBJECT_TYPE_VILLAGER_CHILD", "SCRIPT_OBJECT_TYPE_VILLAGER");
		//coalesceTypes.put("", "");
	}
	
	private static void addBoolOption(String wordTrue, String wordFalse) {
		boolOptions.put(wordTrue + "|" + wordFalse, new String[] {wordTrue, wordFalse});
	}
	
	private static void addBoolOption(String wordTrue) {
		boolOptions.put("[" + wordTrue + "]", new String[] {wordTrue, null});
	}
	
	private static void addBoolInvOption(String wordFalse) {
		boolOptions.put("[" + wordFalse + "]", new String[] {null, wordFalse});
	}
	
	private static void addEnumOption(String keyword, String...options) {
		enumOptions.put(keyword, options);
	}
	
	private final Map<String, String> subtypes = new HashMap<>();
	private final Map<String, Map<Integer, String>> enums = new HashMap<>();
	private final Map<String, String> aliases = new HashMap<>();
	
	private Set<Integer> requiredConstants = new HashSet<>();
	private final Set<String> requiredScripts = new HashSet<>();
	private final Set<String> definedScripts = new HashSet<>();
	private final Map<String, Type[]> scriptsParamTypes = new HashMap<>();
	private final Map<String, Var> globalMap = new HashMap<>();
	private final Map<String, Var> localMap = new HashMap<>();
	private final Map<String, List<Var>> allVars = new HashMap<>();
	private List<Var> localVars;
	private final ArrayList<StackVal> stack = new ArrayList<>();
	private final ArrayList<Integer> argcStack = new ArrayList<>();
	private final ArrayList<Type> typeContextStack = new ArrayList<>();
	private List<Instruction> instructions;
	private ListIterator<Instruction> it;
	private int ip;
	private int lastTracedIp;
	private int nextStatementIndex;
	private Script currentScript;
	private ArrayList<Block> blocks = new ArrayList<>();
	private Block currentBlock = null;
	private boolean inCamera = false;
	private boolean inDialogue = false;
	private boolean inWildKnownCinema = false;
	private boolean requireCamera = false;
	private boolean requireDialogue = false;
	private boolean requireLongCamera = false;
	
	private Writer writer;
	private int lineno;
	private String tabs = "";
	private boolean incTabs = false;
	private int outputDisabled = 0;
	
	private CHLFile chl;
	private Path path;
	
	private PrintStream out;
	private boolean verboseEnabled;
	
	private int heuristicLevel = 2;
	private boolean respectLinenoEnabled = false;
	private boolean defineUnknownEnumsEnabled = false;
	private boolean wildModeEnabled = false;
	
	public CHLDecompiler() {
		this(System.out);
	}
	
	public CHLDecompiler(PrintStream out) {
		this.out = out;
	}
	
	public boolean isVerboseEnabled() {
		return verboseEnabled;
	}
	
	public void setVerboseEnabled(boolean verboseEnabled) {
		this.verboseEnabled = verboseEnabled;
	}
	
	private void warning(String s) {
		out.println(s);
	}
	
	private void notice(String s) {
		if (verboseEnabled) {
			out.println(s);
		}
	}
	
	private void info(String s) {
		if (verboseEnabled) {
			out.println(s);
		}
	}
	
	public int getHeuristicLevel() {
		return heuristicLevel;
	}

	public void setHeuristicLevel(int heuristicLevel) throws IllegalArgumentException {
		if (heuristicLevel < HEURISTIC_MIN || heuristicLevel > HEURISTIC_MAX) {
			throw new IllegalArgumentException("Heuristic level must be between "+HEURISTIC_MIN+" and "+HEURISTIC_MAX);
		}
		this.heuristicLevel = heuristicLevel;
	}

	public boolean isRespectLinenoEnabled() {
		return respectLinenoEnabled;
	}

	public void setRespectLinenoEnabled(boolean respectLinenoEnabled) {
		this.respectLinenoEnabled = respectLinenoEnabled;
	}
	
	public boolean isDefineUnknownEnumsEnabled() {
		return defineUnknownEnumsEnabled;
	}

	public void setDefineUnknownEnumsEnabled(boolean enabled) {
		this.defineUnknownEnumsEnabled = enabled;
	}

	public boolean isWildModeEnabled() {
		return wildModeEnabled;
	}

	public void setWildModeEnabled(boolean wildModeEnabled) {
		this.wildModeEnabled = wildModeEnabled;
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
				if (oldName != null && !entryName.equals(oldName)) {
					notice("NOTICE: entries "+oldName+" and "+entryName+" in "+enumName
							+" share the same value ("+entryVal+")");
				}
			}
		}
	}
	
	public void addAlias(File file) throws FileNotFoundException, IOException, ParseException {
		Pattern pattern = Pattern.compile("global\\s+constant\\s+(.*)\\s*=\\s*(.*)");
		Pattern varDecl = Pattern.compile("global\\s+.*");
		Pattern funcDef = Pattern.compile("define\\s+script\\s+.+");
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
						notice("NOTICE: definition of muliple aliases for symbol "+symbol+" at "+file.getName()+":"+lineno);
					}
				} else if (varDecl.matcher(line).matches()) {
					//Ignore variable declarations
				} else if (funcDef.matcher(line).matches()) {
					//Ignore script definitions
				} else {
					throw new ParseException("Expected: global constant ALIAS = CONSTANT", file, lineno);
				}
			}
		}
	}
	
	private String getSymbol(ArgType type, int val) {
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
		if (!outdir.isDirectory()) {
			outdir.mkdir();
		}
		definedScripts.clear();
		globalMap.clear();
		instructions = chl.code.getItems();
		lastTracedIp = -1;
		currentScript = null;
		mapGlobalVars();
		//Write the list of source files
		List<String> sources = chl.getSourceFilenames();
		Set<String> writtenSources = new HashSet<>();
		File[] renamedSources = new File[sources.size()];
		info("Writing _challenges.txt");
		File listFile = path.resolve("_challenges.txt").toFile();
		try (FileWriter str = new FileWriter(listFile, ASCII);) {
			str.write("//Source files list\r\n");
			if (!aliases.isEmpty()) {
				str.write("_aliases.txt\r\n");
			}
			for (int i = 0; i < sources.size(); i++) {
				final String sourceFilename = sources.get(i);
				final String fixedName = sourceFilename.stripTrailing();	//Remove trailing spaces
				if (!isValidFilename(fixedName)) {
					throw new RuntimeException("Invalid source filename: \""+sourceFilename+"\"");
				}
				//Check for duplicate filenames and compute a new name
				String dir = "";
				File sourceFile = path.resolve(fixedName).toFile();
				if (writtenSources.contains(sourceFile.getPath().toLowerCase())) {
					int index = -1;
					do {
						Path subdir = path.resolve(String.valueOf(++index));
						File subdirFile = subdir.toFile();
						if (!subdirFile.isDirectory()) {
							subdirFile.mkdir();
						}
						sourceFile = subdir.resolve(fixedName).toFile();
					} while (writtenSources.contains(sourceFile.getPath().toLowerCase()));
					dir = index + File.separator;
				}
				writtenSources.add(sourceFile.getPath().toLowerCase());
				renamedSources[i] = sourceFile;	//Store the renamed file
				//
				str.write(dir + sourceFilename + "\r\n");	//It's important to use the name with any trailing spaces
			}
			if (!chl.autoStartScripts.getScripts().isEmpty()) {
				str.write("_autorun.txt\r\n");
			}
		}
		//Write user defined constants (aliases)
		if (!aliases.isEmpty()) {
			info("Writing _aliases.txt");
			File file = path.resolve("_aliases.txt").toFile();
			try (FileWriter str = new FileWriter(file, ASCII);) {
				writer = str;
				lineno = 1;
				writeHeader();
				writeAliases();
			}
		}
		//Write autorun scripts
		if (!chl.autoStartScripts.getScripts().isEmpty()) {
			info("Writing _autorun.txt");
			File file = path.resolve("_autorun.txt").toFile();
			try (FileWriter str = new FileWriter(file, ASCII);) {
				writer = str;
				lineno = 1;
				writeHeader();
				writeAutoStartScripts();
			}
		}
		//Write source files
		if (heuristicLevel >= 3) {
			out.println("Running first scan...");
			outputDisabled++;
			writeSourceFiles(sources, renamedSources);
			outputDisabled--;
			out.println("Running advanced type guessing...");
			resolveTypes();
			out.println("Decompiling...");
		}
		writeSourceFiles(sources, renamedSources);
		//Additional enums
		if (!requiredConstants.isEmpty()) {
			File file = path.resolve("_enums.h").toFile();
			info("Writing "+file.getName());
			try (Writer str = new BufferedWriter(new FileWriter(file, ASCII));) {
				writer = str;
				writeln("enum");
				writeln("{");
				List<Integer> sortedConstants = new ArrayList<>(requiredConstants);
				Collections.sort(sortedConstants);
				for (Integer val : sortedConstants) {
					writeln("\tUNK"+val+" = "+val+",");
				}
				writeln("};");
			}
			out.println("IMPORTANT: please copy content of "+file.getName()+" into Enum.h");
		}
	}
	
	private void writeSourceFiles(List<String> sources, File[] renamedSources) throws IOException, DecompileException {
		int lastGlobal = 0;
		ListIterator<Script> scriptIt = chl.scripts.getItems().listIterator();
		Script script = scriptIt.next();
		for (int fileIndex = 0; fileIndex < sources.size(); fileIndex++) {
			String sourceFilename = sources.get(fileIndex);
			File sourceFile = renamedSources[fileIndex];	//Use the renamed file as output
			requiredScripts.clear();
			info("Writing "+sourceFile.getName());
			try (Writer str = new BufferedWriter(new FileWriter(sourceFile, ASCII));) {
				writer = str;
				lineno = 1;
				writeHeader();
				//Write all scripts in this source file
				while (script.getSourceFilename().equals(sourceFilename)) {
					currentScript = script;
					initLocalVars(script);
					//Heuristics checks
					if (heuristicLevel >= 2) {
						outputDisabled++;
						try {
							decompile(script);
						} catch (DecompileException e) {}
						outputDisabled--;
					}
					//Global variables
					if (lastGlobal < script.getGlobalCount()) {
						writeGlobals(lastGlobal, script.getGlobalCount());
						lastGlobal = script.getGlobalCount();
						writeln("");
					}
					//Script
					decompile(script);
					writeln("");
					if (outputDisabled == 0) {
						definedScripts.add(script.getName());
					}
					//
					if (!scriptIt.hasNext()) {
						script = null;
						break;
					}
					script = scriptIt.next();
				}
			}
			if (!requiredScripts.isEmpty() && outputDisabled == 0) {
				insertRequiredDefinitions(sourceFile);
			}
		}
	}
	
	private void resolveTypes() {
		Set<Var> solvedVars = new HashSet<>();
		Set<Var> varsToSolve = new HashSet<>();
		for (List<Var> tVars : allVars.values()) {
			for (Var var : tVars) {
				if (!var.assignedFrom.isEmpty() || !var.assignedTo.isEmpty()) {
					if (var.type == null || var.type.type == ArgType.FLOAT || var.type.type == ArgType.INT || var.type.isGeneric()) {
						varsToSolve.add(var);
					} else {
						solvedVars.add(var);
					}
				}
			}
		}
		boolean solve = !varsToSolve.isEmpty();
		for (int step = 0; step < RESOLVE_TYPES_MAX_STEPS && solve; step++) {
			solve = false;
			List<Var> varsToMove = new LinkedList<>();
			for (Var varToSolve : varsToSolve) {
				if (!varToSolve.assignedFrom.isEmpty()) {
					for (Var solvedVar : varToSolve.assignedFrom) {
						if (solvedVars.contains(solvedVar)) {
							varToSolve.type = solvedVar.type;
							varsToMove.add(varToSolve);
							solve = true;
							info("INFO: guessed type for "+varToSolve+": "+varToSolve.type+" (copied from "+solvedVar+" at step "+step+")");
							break;
						}
					}
				} else if (!varToSolve.assignedTo.isEmpty()) {
					for (Var solvedVar : varToSolve.assignedTo) {
						if (solvedVars.contains(solvedVar)) {
							varToSolve.type = solvedVar.type;
							varsToMove.add(varToSolve);
							solve = true;
							info("INFO: guessed type for "+varToSolve+": "+varToSolve.type+" (copied from "+solvedVar+" at step "+step+")");
							break;
						}
					}
				}
			}
			solvedVars.clear();
			for (Var var : varsToMove) {
				varsToSolve.remove(var);
				solvedVars.add(var);
			}
		}
	}
	
	private void insertRequiredDefinitions(File sourceFile) throws DecompileException, IOException {
		trace("Inserting required definitions in "+sourceFile.getName());
		File tmpFile = path.resolve("_tmp.txt").toFile();
		sourceFile.renameTo(tmpFile);
		try (BufferedReader reader = new BufferedReader(new FileReader(tmpFile, ASCII));
				Writer str = new BufferedWriter(new FileWriter(sourceFile, ASCII));) {
			writer = str;
			List<String> header = new LinkedList<>();
			//Copy everything before first script
			String line = reader.readLine();
			while (line != null && !line.startsWith("begin ")) {
				header.add(line);
				line = reader.readLine();
			}
			//Try to make room for new statements
			int requiredSpace = requiredScripts.size() + 1;
			for (int i = 0; i < requiredSpace; i++) {
				if (header.isEmpty()) break;
				if (!getLast(header).isBlank()) break;
				Utils.pop(header);
			}
			//Write header
			for (String h : header) {
				writeln(h);
			}
			writeln("");
			//Insert required scripts
			for (String name : requiredScripts) {
				try {
					Script script = chl.scripts.getScript(name);
					writeln("define "+script.getSignature());
				} catch (ScriptNotFoundException e) {
					throw new DecompileException(e.getMessage());
				}
			}
			writeln("");
			//Copy code
			while (line != null) {
				writeln(line);
				line = reader.readLine();
			}
		}
		tmpFile.delete();
	}
	
	private void mapGlobalVars() {
		List<InitGlobal> initGlobals = chl.initGlobals.getItems();
		Map<String, Float> initMap = new HashMap<>();
		for (InitGlobal init : initGlobals) {
			String name = init.getName();
			if (!"LHVMA".equals(name)) {
				initMap.put(name, init.getFloat());
			}
		}
		//
		List<String> globalNames = chl.globalVars.getNames();
		List<Var> globalVars = new ArrayList<>(globalNames.size());
		Var var = null;
		int index = 0;
		for (String name : globalNames) {
			index++;
			if ("LHVMA".equals(name)) {
				var.size++;
			} else {
				float val = initMap.getOrDefault(name, 0f);
				var = new Var(null, name, index, 1, val);
				globalMap.put(name, var);
				globalVars.add(var);
			}
		}
		//
		allVars.put(null, globalVars);
	}
	
	private void writeln(String string) throws IOException {
		if (outputDisabled == 0) {
			writer.write(string + "\r\n");
			lineno++;
		}
	}
	
	private void writeHeader() throws IOException {
		if (!respectLinenoEnabled) {
			writeln("//LHVM Challenge source version "+chl.header.getVersion());
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
		List<Integer> scripts = chl.autoStartScripts.getScripts();
		for (int i = scripts.size() - 1; i >= 0; i--) {
			int scriptID = scripts.get(i);
			try {
				Script script = chl.scripts.getScript(scriptID);
				writeln("run script "+script.getName());
			} catch (InvalidScriptIdException e) {
				String msg = "Invalid autorun script id: " + scriptID;
				writeln("//" + msg);
				throw new DecompileException(msg);
			}
		}
	}
	
	private void writeGlobals(int start, int end) throws IOException {
		List<String> names = chl.globalVars.getNames().subList(start, end);
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
	
	private void initLocalVars(Script script) {
		localVars = allVars.get(script.getName());
		boolean copyTypes = localVars instanceof TempList;
		if (localVars == null || copyTypes) {
			List<String> vars = script.getVariables();
			List<Var> refVars = copyTypes ? localVars : null;
			localVars = new ArrayList<>(vars.size());
			Var var = null;
			int index = 0;
			int id = script.getGlobalCount();
			for (String name : vars) {
				id++;
				if ("LHVMA".equals(name)) {
					var.size++;
				} else {
					boolean isArg = localVars.size() < script.getParameterCount();
					var = new Var(script, name, id, 1, 0, isArg, false);
					if (isArg && refVars != null) {
						Var refVar = refVars.get(index);
						var.type = refVar.type;
						for (Var tVar : refVar.assignedFrom) {
							var.assignedFrom.add(tVar);
							tVar.assignedTo.add(var);
						}
						for (Var tVar : refVar.assignedTo) {
							var.assignedTo.add(tVar);
							tVar.assignedFrom.add(var);
						}
					}
					localVars.add(var);
					index++;
				}
			}
			allVars.put(script.getName(), localVars);
		}
		localMap.clear();
		for (Var v : localVars) {
			localMap.put(v.name, v);
		}
	}
	
	private void decompile(Script script) throws IOException, DecompileException {
		stack.clear();
		blocks.clear();
		try {
			scriptsParamTypes.putIfAbsent(script.getName(), new Type[script.getParameterCount()]);
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
			for (int i = 0; i < localVars.size(); i++) {
				Var var = localVars.get(i);
				if (i < script.getParameterCount()) {								//parameter
					accept(OPCode.POP, OPCodeMode.REF, DataType.FLOAT, var.index);
				} else if (var.isArray()) {											//array
					writeln("\t"+var.name+"["+var.size+"]");
				} else {															//atomic var
					Expression statement = decompileLocalVarAssignment(var);
					if (respectLinenoEnabled) alignToLineno();
					writeln("\t"+statement);
				}
			}
			//START
			accept(OPCode.ENDEXCEPT, OPCodeMode.FREE, DataType.INT, 0);
			writeln("start");
			//
			tabs = "\t";
			incTabs = false;
			Context knownContext = null;
			knownContext = getKnownContext(script);
			if (knownContext == Context.CINEMA) {
				writeln(tabs + "begin known cinema");
				tabs += "\t";
				inCamera = true;
				inDialogue = true;
			} else if (knownContext == Context.DIALOGUE) {
				writeln(tabs + "begin known dialogue");
				tabs += "\t";
				inDialogue = true;
			}
			//
			Expression statement = decompileNextStatement();
			while (statement != END_SCRIPT) {
				if (statement != null) {
					if (wildModeEnabled && !inWildKnownCinema && (requireCamera && !inCamera || requireDialogue && !inDialogue)) {
						writeln(tabs + "begin known cinema");
						tabs += "\t";
						inWildKnownCinema = true;
					}
					//
					if (respectLinenoEnabled) alignToLineno();
					writeln(tabs + statement);
					//
					if (wildModeEnabled && inWildKnownCinema && !requireLongCamera) {
						decTabs();
						writeln(tabs + "end known cinema");
						inWildKnownCinema = false;
					}
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
				warning("Stack is not empty at end of script "+script.getName()+":");
				warning(stack.toString());
				warning("");
			}
			if (!blocks.isEmpty()) {
				warning("Blocks stack is not empty at end of script "+script.getName()+":");
				warning("");
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
		if (outputDisabled == 0) {
			while (lineno < target) {
				writeln("");
			}
		}
	}
	
	private Expression decompileLocalVarAssignment(Var var) throws DecompileException {
		int start = ip;
		Instruction popf = find(OPCode.POP, OPCodeMode.REF, DataType.FLOAT, var.index);
		if (popf == null) {
			gotoAddress(start);
			return new Expression(var.name+"[1]");
		} else {
			nextStatementIndex = ip + 1;
			Expression statement = decompile();
			gotoAddress(nextStatementIndex);
			return statement;
		}
	}
	
	private Expression decompileNextStatement() throws DecompileException {
		findEndOfStatement();
		nextStatementIndex = ip + 1;
		requireCamera = false;
		requireDialogue = false;
		Expression statement = decompile();
		gotoAddress(nextStatementIndex);
		return statement;
	}
	
	private Expression decompile() throws DecompileException {
		Expression op1, op2, op3;
		String varIndex;
		Instruction pInstr, nInstr, nInstr2;
		int start;
		Set<Integer> tmpConstants;
		Instruction instr = prev();
		switch (instr.opcode) {
			case ADD:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					if (op2.isNumber() && op2.floatVal() != null && op2.floatVal() == 1) {
						return new Expression("++");
					} else {
						return new Expression(" += " + op2);
					}
				} else {
					return new Expression(Priority.ADD, op1.wrap(Priority.ADD) + " + " + op2.wrap(Priority.ADD));
				}
			case SUB:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					if (op2.isNumber() && op2.floatVal() != null && op2.floatVal() == 1) {
						return new Expression("--");
					} else {
						return new Expression(" -= " + op2);
					}
				} else {
					return new Expression(Priority.SUB, op1.wrap(Priority.SUB) + " - " + op2.wrap(Priority.SUB2));
				}
			case MUL:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					return new Expression(" *= " + op2);
				} else {
					return new Expression(Priority.MUL, op1.wrap(Priority.MUL0) + " * " + op2.wrap(Priority.MUL));
				}
			case MOD:
				op2 = decompile();
				op1 = decompile();
				return new Expression(Priority.MOD, op1.wrap(Priority.MOD) + " % " + op2.wrap(Priority.MOD2));
			case DIV:
				op2 = decompile();
				op1 = decompile();
				if (op1 == SELF_ASSIGN) {
					return new Expression(" /= " + op2);
				} else {
					return new Expression(Priority.DIV, op1.wrap(Priority.DIV) + " / " + op2.wrap(Priority.DIV2));
				}
			case AND:
				op2 = decompile();
				op1 = decompile();
				return new Expression(Priority.AND, op1.wrap(Priority.AND0) + " and " + op2.wrap(Priority.AND));
			case OR:
				op2 = decompile();
				op1 = decompile();
				return new Expression(Priority.OR, op1.wrap(Priority.OR0) + " or " + op2.wrap(Priority.OR));
			case NOT:
				op1 = decompile();
				return new Expression(Priority.NOT, "not " + op1.wrap(Priority.NOT));
			case CAST:
				switch (instr.dataType) {
					case INT:
						op1 = decompile();
						if (op1.isVar()) {
							return new Expression("constant "+op1.wrap(Priority.CONST_EXPR), Priority.CONST_EXPR, op1.getVar());
						}
						return new Expression("constant "+op1.wrap(Priority.CONST_EXPR), Priority.CONST_EXPR, op1.type);
					case FLOAT:
						op1 = decompile();
						if (op1.isNumber() && !op1.isExpression) {
							if (defineUnknownEnumsEnabled) {
								if (outputDisabled == 0) {
									requiredConstants.add(op1.intVal());
								}
								return new Expression("variable UNK" + op1.intVal(), op1.type);
							} else {
								//Workaround for missing constants
								return new Expression(op1.intVal());
							}
						} else {
							return new Expression("variable " + op1.wrapExpression(), op1.type);
						}
					case COORDS:
						typeContextStack.add(Type.FLOAT);
						op3 = decompile();
						pInstr = prev();	//CASTC
						verify(ip, pInstr, OPCode.CAST, 1, DataType.COORDS);
						op2 = decompile();
						pInstr = prev();	//CASTC
						verify(ip, pInstr, OPCode.CAST, 1, DataType.COORDS);
						op1 = decompile();
						Utils.pop(typeContextStack);
						return new Expression("[" + op1 + ", " + op2 + ", " + op3 + "]", Priority.ATOMIC, Type.COORD);
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
							typeContextStack.add(Type.OBJECT);
							op1 = decompile();	//OBJECT
							Utils.pop(typeContextStack);
							return new Expression(op1 + " is " + property);
						} else {
							//PROPERTY of VARIABLE
							typeContextStack.add(Type.OBJECT);
							op1 = decompile();	//variable
							Utils.pop(typeContextStack);
							pInstr = prev();	//PUSHI int
							verify(ip, pInstr, OPCode.PUSH, 1, DataType.INT);
							int propertyId = pInstr.intVal;
							String property = getSymbol(NativeFunction.GET_PROPERTY.args[0].type, propertyId);
							return new Expression(property + " of " + op1);
						}
					} else if (func == NativeFunction.SET_PROPERTY) {
						op2 = decompile();
						pInstr = peek(-1);
						if (pInstr.opcode == OPCode.POP && pInstr.mode == 1) {
							//PROPERTY of VARIABLE = EXPRESSION
							pInstr = prev();	//POPI
							verify(ip, pInstr, OPCode.POP, 1, DataType.INT);
							pInstr = prev();	//SYS2 GET_PROPERTY
							verify(ip, pInstr, NativeFunction.GET_PROPERTY);
							pInstr = prev();	//DUP 1
							verify(ip, pInstr, OPCode.DUP, 0, DataType.NONE, 1);
							pInstr = prev();	//DUP 1
							verify(ip, pInstr, OPCode.DUP, 0, DataType.NONE, 1);
							typeContextStack.add(Type.OBJECT);
							op1 = decompile();	//variable
							Utils.pop(typeContextStack);
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
							typeContextStack.add(Type.OBJECT);
							op1 = decompile();	//variable
							Utils.pop(typeContextStack);
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
					Script script = chl.scripts.getScript(instr.intVal);
					//If the script hasnt been defined yet, add it to the required "define script..."
					if (!definedScripts.contains(script.getName())) {
						requiredScripts.add(script.getName());
					}
					//Retrieve script parameter types
					List<Var> scriptVars = allVars.get(script.getName());
					Type[] paramTypes = scriptsParamTypes.get(script.getName());
					if (paramTypes == null) {
						paramTypes = new Type[script.getParameterCount()];
						scriptsParamTypes.put(script.getName(), paramTypes);
						assert scriptVars == null;
						scriptVars = new TempList<>();
						for (int i = 0; i < script.getParameterCount(); i++) {
							scriptVars.add(new Var(script, "_arg"+i, -1, 1, 0f));
						}
						allVars.put(script.getName(), scriptVars);
					}
					//
					String line = "run";
					if (instr.isStart()) {
						line += " background";
					}
					line += " script " + script.getName();
					if (script.getParameterCount() > 0) {
						boolean guessed = false;
						typeContextStack.add(paramTypes[paramTypes.length - 1]);
						int i = paramTypes.length - 1;
						Expression param = decompile();
						if (paramTypes[i] == null && param != null && param.type != null) {
							paramTypes[i] = param.type;
							guessed = true;
						}
						if (param.isVar()) {
							Var var = param.getVar();
							Var arg = scriptVars.get(i);
							var.assignedTo.add(arg);
							arg.assignedFrom.add(var);
						}
						String params = param.toString();
						Utils.pop(typeContextStack);
						for (i--; i >= 0; i--) {
							typeContextStack.add(paramTypes[i]);
							param = decompile();
							if (paramTypes[i] == null && param != null && param.type != null) {
								paramTypes[i] = param.type;
								guessed = true;
							}
							if (param.isVar()) {
								Var var = param.getVar();
								Var arg = scriptVars.get(i);
								var.assignedTo.add(arg);
								arg.assignedFrom.add(var);
							}
							params = param + ", " + params;
							Utils.pop(typeContextStack);
						}
						line += "(" + params + ")";
						if (guessed) {
							info("INFO: guessed parameter types: "+script.getName()+"("+join(", ", paramTypes)+")");
						}
					}
					return new Expression(line);
				} catch (InvalidScriptIdException e) {
					throw new DecompileException(currentScript, ip, e);
				}
			case EQ:
				start = ip;
				tmpConstants = new HashSet<>(requiredConstants);
				op2 = decompile();
				op1 = decompile();
				if (heuristicLevel >= 1) {
					if (op1.type == Type.INT && op2.isVar()) {
						Var var = op2.getVar();
						gotoAddress(start);
						requiredConstants = tmpConstants;
						op2 = decompile();
						typeContextStack.add(var.type);
						op1 = decompile();
						Utils.pop(typeContextStack);
						setVarType(var, op1);
					} else if (op1.isVar() && op2.type == Type.INT) {
						Var var = op1.getVar();
						gotoAddress(start);
						requiredConstants = tmpConstants;
						typeContextStack.add(var.type);
						op2 = decompile();
						Utils.pop(typeContextStack);
						op1 = decompile();
						setVarType(var, op2);
					} else if (op1.isVar() && op2.isVar()) {
						Var var1 = op1.getVar();
						Var var2 = op2.getVar();
						var1.assignedFrom.add(var2);
						var1.assignedTo.add(var2);
						var2.assignedFrom.add(var1);
						var2.assignedTo.add(var1);
					}
				}
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
				return new Expression("-" + op1.wrap(Priority.NEG));
			case NEQ:
				start = ip;
				tmpConstants = new HashSet<>(requiredConstants);
				op2 = decompile();
				op1 = decompile();
				if (heuristicLevel >= 1) {
					if (op1.type == Type.INT && op2.isVar()) {
						Var var = op2.getVar();
						gotoAddress(start);
						requiredConstants = tmpConstants;
						op2 = decompile();
						typeContextStack.add(var.type);
						op1 = decompile();
						Utils.pop(typeContextStack);
						setVarType(var, op1);
					} else if (op1.isVar() && op2.type == Type.INT) {
						Var var = op1.getVar();
						gotoAddress(start);
						requiredConstants = tmpConstants;
						typeContextStack.add(var.type);
						op2 = decompile();
						Utils.pop(typeContextStack);
						op1 = decompile();
						setVarType(var, op2);
					} else if (op1.isVar() && op2.isVar()) {
						Var var1 = op1.getVar();
						Var var2 = op2.getVar();
						var1.assignedFrom.add(var2);
						var1.assignedTo.add(var2);
						var2.assignedFrom.add(var1);
						var2.assignedTo.add(var1);
					}
				}
				return new Expression(op1 + " != " + op2);
			case POP:
				if (instr.isReference()) {
					//IDENTIFIER = EXPRESSION
					VarWithIndex vari = getVar(instr.intVal);
					Var var = vari.var;
					if (var.type != null) {
						typeContextStack.add(var.type);
					}
					op2 = decompile();
					if (var.type != null) {
						Utils.pop(typeContextStack);
					}
					setVarType(var, op2);
					return new Expression(vari + " = " + op2);
				} else {
					//statement
					return decompile();
				}
			case PUSH:
				if (instr.isReference()) {
					VarWithIndex vari = getVar(instr.intVal);
					Var var = vari.var;
					if (!typeContextStack.isEmpty()) {
						Type type = getLast(typeContextStack);
						setVarType(var, type);
					}
					if (var.isArray()) {
						//IDENTIFIER\[NUMBER\]
						return new Expression(vari.toString(), vari.var);
					} else {
						//IDENTIFIER
						return new Expression(var);
					}
				} else {
					//NUMBER
					switch (instr.dataType) {
						case FLOAT:
							return new Expression(instr.floatVal);
						case INT:
							if (!typeContextStack.isEmpty()) {
								Type type = getLast(typeContextStack);
								if (type != null) {
									if (type.isEnum()) {
										String entry = getEnumEntry(type.toString(), instr.intVal);
										if (entry != null) {
											String alias = aliases.getOrDefault(entry, entry);
											return new Expression(alias, Priority.ATOMIC, Type.INT, instr.intVal);
										}
									} else if (type.type == ArgType.STRPTR) {
										String string = chl.data.getString(instr.intVal);
										return new Expression(escape(string), Priority.ATOMIC, Type.STRPTR, instr.intVal);
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
							return new Expression("["+op1+", "+op2+", "+op3+"]", Priority.ATOMIC, Type.COORD);
						case OBJECT:
							if (instr.intVal != 0) {
								throw new DecompileException("Expected 0 after PUSHO", currentScript, ip, instr);
							}
							return new Expression(null, Priority.ATOMIC, Type.OBJECT, 0);
						case BOOLEAN:
							return new Expression(instr.boolVal);
						case VAR:
							VarWithIndex vari = getVar(instr.intVal);
							Var var = vari.var;
							return new Expression(var);
						default:
					}
				}
				break;
			case REF_PUSH:
				if (instr.mode == 2) {
					//IDENTIFIER\[EXPRESSION\]
					verify(ip, instr, OPCode.REF_PUSH, OPCodeMode.REF, DataType.VAR);
					pInstr = prev();	//ADDF
					verify(ip, pInstr, OPCode.ADD, 1, DataType.FLOAT);
					pInstr = prev();	//PUSHF var
					verify(ip, pInstr, OPCode.PUSH, 1, DataType.FLOAT);
					VarWithIndex vari = getVar((int)pInstr.floatVal);
					varIndex = decompile().toString();
					return new Expression(vari.var.name + "[" + varIndex + "]", vari.var);
				} else {
					//&VARIABLE
					verify(ip, instr, OPCode.REF_PUSH, 1, DataType.VAR);
					pInstr = prev();	//PUSHV var
					verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
					VarWithIndex vari = getVar(pInstr.intVal);
					return new Expression("&"+vari, vari.var);
				}
			case REF_ADD_PUSH:
				if (instr.dataType == DataType.FLOAT) {
					//REFERENCE\[EXPRESSION\]
					pInstr = prev();	//PUSHV [var]
					verify(ip, pInstr, OPCode.PUSH, OPCodeMode.REF, DataType.VAR);
					VarWithIndex vari = getVar(pInstr.intVal);
					Var var = vari.var;
					if (!var.ref) {
						trace("TRACE: variable "+vari+" is a reference");
						var.ref = true;
						currentScript.setReference(var.name);
					}
					op2 = decompile();
					return new Expression(vari.var.name+"["+op2+"]", vari.var);
				} else if (instr.dataType == DataType.VAR) {
					if (instr.mode == 2) {
						//&IDENTIFIER\[EXPRESSION\]
						pInstr = prev();	//PUSHV var
						verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
						VarWithIndex vari = getVar(pInstr.intVal);
						op2 = decompile();
						return new Expression("&"+vari.var.name+"["+op2+"]", vari.var);
					} else {
						//REFERENCE\[NUMBER\]
						pInstr = prev();	//PUSHF index
						verify(ip, pInstr, OPCode.PUSH, 1, DataType.FLOAT);
						varIndex = String.valueOf((int)pInstr.floatVal);
						pInstr = prev();	//PUSHV [var]
						verify(ip, pInstr, OPCode.PUSH, OPCodeMode.REF, DataType.VAR);
						VarWithIndex vari = getVar(pInstr.intVal);
						Var var = vari.var;
						if (!var.ref) {
							trace("TRACE: variable "+vari+" is a reference");
							var.ref = true;
							currentScript.setReference(var.name);
						}
						return new Expression(var.name+"["+varIndex+"]", vari.var);
					}
				}
				break;
			case REF_AND_OFFSET_PUSH:
				next();
				return SELF_ASSIGN;
			case REF_AND_OFFSET_POP:
				start = ip;
				tmpConstants = new HashSet<>(requiredConstants);
				op2 = decompile();
				pInstr = peek(-1);
				if (pInstr.opcode == OPCode.POP && pInstr.mode == 1) {
					//IDENTIFIER = EXPRESSION
					//IDENTIFIER\[EXPRESSION\] = EXPRESSION
					pInstr = prev();	//POPI
					verify(ip, pInstr, OPCode.POP, 1, DataType.INT);
					pInstr = prev();	//REF_AND_OFFSET_PUSH
					verify(ip, pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeMode.REF, DataType.VAR);
					pInstr = prev();	//PUSHV var
					verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
					VarWithIndex vari = getVar(pInstr.intVal);
					varIndex = decompile().toString();
					Var var = vari.var;
					if (heuristicLevel >= 1 && var.type != null) {
						gotoAddress(start);
						requiredConstants = tmpConstants;
						typeContextStack.add(var.type);
						op2 = decompile();
						Utils.pop(typeContextStack);
						pInstr = prev();	//POPI
						pInstr = prev();	//REF_AND_OFFSET_PUSH
						pInstr = prev();	//PUSHV var
						varIndex = decompile().toString();
					}
					setVarType(var, op2);
					String assignee = var.isArray() ? var.name+"["+varIndex+"]" : var.name;
					return new Expression(assignee + " = " + op2);
				} else if (pInstr.opcode == OPCode.REF_AND_OFFSET_PUSH && pInstr.mode == OPCodeMode.REF) {
					//IDENTIFIER += EXPRESSION
					//IDENTIFIER\[EXPRESSION\] += EXPRESSION
					pInstr = prev();	//REF_AND_OFFSET_PUSH
					verify(ip, pInstr, OPCode.REF_AND_OFFSET_PUSH, OPCodeMode.REF, DataType.VAR);
					pInstr = prev();	//PUSHV var
					verify(ip, pInstr, OPCode.PUSH, 1, DataType.VAR);
					VarWithIndex vari = getVar(pInstr.intVal);
					varIndex = decompile().toString();
					Var var = vari.var;
					String assignee = var.isArray() ? var.name+"["+varIndex+"]" : var.name;
					return new Expression(assignee + op2.toString());
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
					verify(jmpExceptionHandlerEndIp, jmpExceptionHandlerEnd, OPCode.JMP, OPCodeMode.FORWARD, DataType.INT);
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
							if (op1.isBool() && op1.boolVal() == Boolean.TRUE) {
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
							if (jmpSkipCase.mode == OPCodeMode.FORWARD) {
								//if CONDITION
								while (jmpSkipCase.opcode == OPCode.JMP && jmpSkipCase.isForward() && jmpSkipCase.intVal > jmpSkipCaseIp + 1) {
									jmpSkipCaseIp = jmpSkipCase.intVal;
									jmpSkipCase = instructions.get(jmpSkipCaseIp);
								}
								//
								pushBlock(new Block(beginIndex, BlockType.IF, endThenIp));
								currentBlock.farEnd = jmpSkipCaseIp;
								return new Expression("if " + op1);
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
										inCamera = true;
										inDialogue = true;
										return new Expression("begin cinema");
									} else {
										this.nextStatementIndex += 3;
										incTabs = true;
										inCamera = true;
										return new Expression("begin camera");
									}
								}
							} else {
								incTabs = true;
								inDialogue = true;
								return new Expression("begin dialogue");
							}
						} else {
							//wait until CONDITION
							op1 = decompile();
							return new Expression("wait until " + op1);
						}
					}
				}
				break;
			case EXCEPT:
				final int exceptionHandlerBegin = instr.intVal;
				final int beginIndex = ip + 1;
				Instruction jmp = instructions.get(exceptionHandlerBegin - 1);
				if (jmp.opcode == OPCode.JMP && jmp.intVal == beginIndex) {
					//begin loop
					gotoAddress(beginIndex);
					pushBlock(new Block(beginIndex, BlockType.LOOP, exceptionHandlerBegin - 1, exceptionHandlerBegin));
					return new Expression("begin loop");
				} else {
					//while CONDITION
					next();				//Skip EXCEPT
					Instruction jz = findEndOfStatement();
					verify(ip, jz, OPCode.JZ, OPCodeMode.FORWARD, DataType.INT);
					nextStatementIndex = ip + 1;
					int endWhileIp = jz.intVal;
					prev();				//Go before JZ
					op1 = decompile();	//CONDITION
					pushBlock(new Block(beginIndex, BlockType.WHILE, endWhileIp - 1, exceptionHandlerBegin));
					return new Expression("while " + op1);
				}
				//return null;
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
							&& pInstr.mode == 1
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
				return new Expression(op1.wrapExpression() + " seconds");
			case SQRT:
				op1 = decompile();
				return new Expression("sqrt " + op1.wrapExpression());
			case TAN:
				op1 = decompile();
				return new Expression("tan " + op1.wrapExpression());
			case SIN:
				op1 = decompile();
				return new Expression("sin " + op1.wrapExpression());
			case COS:
				op1 = decompile();
				return new Expression("cos " + op1.wrapExpression());
			case ATAN:
				op1 = decompile();
				return new Expression("arctan " + op1.wrapExpression());
			case ASIN:
				op1 = decompile();
				return new Expression("arcsin " + op1.wrapExpression());
			case ACOS:
				op1 = decompile();
				return new Expression("arccos " + op1.wrapExpression());
			case ATAN2:
				op2 = decompile();
				op1 = decompile();
				return new Expression("arctan2 "+op1.wrapExpression()+" over "+op2.wrapExpression());
			case ABS:
				op1 = decompile();
				return new Expression("abs " + op1.wrapExpression());
			case NOP:
				break;	//Never found
			case END:
				return END_SCRIPT;
		}
		throw new DecompileException("Unsupported instruction in "+currentBlock+" "+currentBlock.begin, currentScript, ip, instr);
	}
	
	private void setVarType(Var var, Var refVar) {
		var.assignedFrom.add(refVar);
		refVar.assignedTo.add(var);
		setVarType(var, refVar.type);
	}
	
	private void setVarType(Var var, ArgType type, String specificType) {
		setVarType(var, new Type(type, specificType));
	}
	
	private void setVarType(Var var, Expression expr) {
		if (expr.isVar()) {
			setVarType(var, expr.getVar());
		} else {
			setVarType(var, expr.type);
		}
	}
	
	private void setVarType(Var var, Type newType) {
		if (newType != null && newType.type != ArgType.UNKNOWN) {
			Type oldType = var.type;
			if (oldType == null) {
				//First time the type is assigned, everything smooth
				var.type = newType;
				if (newType != Type.FLOAT) {
					info("INFO: guessed type for "+var+": "+newType+" (in "+currentScript.getName()+")");
				} else if (traceEnabled) {
					trace("TRACE: guessed type for "+var+": "+newType+" (in "+currentScript.getName()+")");
				}
			} else if (!newType.equals(oldType)) {
				if (oldType.type == ArgType.FLOAT) {
					//Always overwrite float with other types
					var.type = newType;
					if (!newType.isObject()) {
						info("INFO: guessed type for "+var+": "+newType+" (in "+currentScript.getName()+")");
					}
				} else if (newType.type == ArgType.FLOAT) {
					//Ignore float values set by hand on non-float variables
				} else if (oldType.type == ArgType.INT && newType.isEnum()) {
					//Enums are better than int, overwrite
					var.type = newType;
					info("INFO: new guessed type for "+var+": "+newType+" (in "+currentScript.getName()+")");
				} else if (oldType.isGeneric() && newType.isSpecific()) {
					//Specific types are better than generic, overwrite
					var.type = newType;
					info("INFO: new guessed type for "+var+": "+newType+" (in "+currentScript.getName()+")");
				} else if (oldType.isEnum() && newType.type == ArgType.INT) {
					//Ignore generic int
				} else if (oldType.isSpecificEnum() && newType.isGenericEnum()) {
					//Ignore generic enums
				} else if (oldType.isSpecificObject() && newType.isGenericObject()) {
					//Ignore generic Object types
				} else if (oldType != Type.UNKNOWN) {
					notice("NOTICE: type of "+var+" in "+currentScript.getSourceFilename()+":"+lineno
						+" is ambiguous (found both "+oldType+" and "+newType+")");
					var.type = Type.UNKNOWN;
				}
			}
		}
	}
	
	private List<Expression> readParameters(NativeFunction func) throws DecompileException {
		String subtype = null;
		int argc = 0;
		boolean guessed = false;
		Type[] scriptParamTypes = scriptsParamTypes.get(currentScript.getName());
		List<Expression> params = new LinkedList<>();
		for (int i = func.args.length - 1; i >= 0; i--) {
			Argument arg = func.args[i];
			if (i > 0 && func.args[i - 1].varargs) {
				Expression expr = decompile();
				params.add(0, expr);
				argc = expr.intVal();
			} else if (arg.varargs) {
				if (argc > 0) {
					Type[] calledScriptParamTypes = null;
					if (heuristicLevel >= 2) {
						final int pos = ip;
						for (int j = 0; j < argc; j++) {
							decompile();
						}
						int strptr = decompile().intVal();
						String calledScriptName = chl.data.getString(strptr);
						calledScriptParamTypes = scriptsParamTypes.get(calledScriptName);
						gotoAddress(pos);
					}
					//
					int j = argc - 1;
					if (calledScriptParamTypes != null) {
						typeContextStack.add(calledScriptParamTypes[j]);
					}
					String argv = decompile().toString();
					if (calledScriptParamTypes != null) {
						Utils.pop(typeContextStack);
					}
					for (j--; j >= 0; j--) {
						if (calledScriptParamTypes != null) {
							typeContextStack.add(calledScriptParamTypes[j]);
						}
						argv = decompile() + ", " + argv;
						if (calledScriptParamTypes != null) {
							Utils.pop(typeContextStack);
						}
					}
					params.add(0, new Expression(argv));
				} else {
					params.add(0, new Expression(""));
				}
			} else {
				Type context;
				if ((func==NativeFunction.RANDOM_ULONG || func==NativeFunction.RANDOM) && !typeContextStack.isEmpty()) {
					context = getLast(typeContextStack);
				} else {
					context = new Type(arg.type, subtype);
				}
				typeContextStack.add(context);
				subtype = null;
				//
				Expression expr = decompile();
				if (expr.isCoord() && arg.type != ArgType.COORD) {
					throw new DecompileException(func+" expects parameter "+i+" to be a "+arg.type.keyword
							+", but received Coord, this would corrupt the stack", currentScript, ip, instructions.get(ip));
				}
				params.add(0, expr);
				//
				if (arg.type == ArgType.AUDIO_SFX_BANK_TYPE && expr.isNumber()) {
					int val = expr.intVal();
					String entry = getEnumEntry(ArgType.AUDIO_SFX_BANK_TYPE, val);
					subtype = subtypes.get(entry);
				}
				Utils.pop(typeContextStack);
				//
				if (expr.isVar()) {
					//Guess variable type
					Var var = expr.getVar();
					setVarType(var, arg.type, arg.objectClass);
					//Guess current script parameter types
					int localIndex = var.index - currentScript.getGlobalCount() - 1;
					if (localIndex >= 0 && localIndex < currentScript.getParameterCount()) {
						Type oldType = scriptParamTypes[localIndex];
						Type newType = var.type;
						if (newType == null) {
							//Nothing to do here
						} else if (oldType == null) {
							//First time the type is assigned, everything smooth
							scriptParamTypes[localIndex] = newType;
							guessed = true;
						} else if (!newType.equals(oldType)) {
							if (oldType.type == ArgType.FLOAT) {
								//Always overwrite float with other types
								scriptParamTypes[localIndex] = newType;
								guessed = true;
							} else if (newType.type == ArgType.FLOAT) {
								//Ignore float values set by hand on non-float variables
							} else if (oldType.type == ArgType.INT && newType.isEnum()) {
								//Enums are better than int, overwrite
								scriptParamTypes[localIndex] = newType;
								guessed = true;
							} else if (oldType.isGeneric() && newType.isSpecific()) {
								//Specific types are better than generic, overwrite
								scriptParamTypes[localIndex] = newType;
								guessed = true;
							} else if (oldType.isEnum() && newType.type == ArgType.INT) {
								//Ignore generic int
							} else if (oldType.isSpecificEnum() && newType.isGenericEnum()) {
								//Ignore generic enums
							} else if (oldType.isSpecificObject() && newType.isGenericObject()) {
								//Ignore generic Object types
							} else if (oldType != Type.UNKNOWN) {
								notice("NOTICE: type of parameter "+localIndex+" in "
										+currentScript.getSourceFilename()+":"+lineno
										+" is ambiguous (found both "+oldType+" and "+newType+")");
								scriptParamTypes[localIndex] = Type.UNKNOWN;
							}
						}
					}
				}
			}
		}
		if (guessed) {
			info("INFO: guessed parameter types: "+currentScript.getName()+"("+join(", ", scriptParamTypes)+")");
		}
		return params;
	}
	
	private void pushBlock(Block block) {
		blocks.add(block);
		currentBlock = block;
		incTabs = true;
	}
	
	private void popBlock() throws DecompileException {
		Utils.pop(blocks);
		currentBlock = blocks.isEmpty() ? null : getLast(blocks);
		decTabs();
	}
	
	private Expression decompile(NativeFunction func) throws DecompileException {
		Instruction pInstr, nInstr;
		boolean anti;
		String line;
		//Set required context
		if (func.context == Context.CAMERA) {
			requireCamera = true;
		} else if (func.context == Context.DIALOGUE) {
			requireDialogue = true;
		}
		if (func == NativeFunction.START_CANNON_CAMERA) {
			requireLongCamera = true;
		} else if (func == NativeFunction.END_CANNON_CAMERA) {
			requireLongCamera = false;
		}
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
					inCamera = false;
					inDialogue = false;
					requireCamera = false;
					requireDialogue = false;
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
					inCamera = false;
					requireCamera = false;
					return new Expression("end cinema with dialogue");
				}
			case END_GAME_SPEED:
				accept(NativeFunction.END_GAME_SPEED);
				accept(NativeFunction.END_CAMERA_CONTROL);
				accept(NativeFunction.END_DIALOGUE);
				this.nextStatementIndex = ip + 1;
				decTabs();
				inCamera = false;
				requireCamera = false;
				return new Expression("end camera");
			case RELEASE_DUAL_CAMERA:
				decTabs();
				return new Expression("end dual camera");
			case END_CANNON_CAMERA:
				decTabs();
				return new Expression("end cannon");
			case END_DIALOGUE:
				decTabs();
				inDialogue = false;
				requireDialogue = false;
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
						line = "marker at "+params.get(2).wrapCoordExpr();
						return new Expression(line, Priority.OBJECT, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_MARKER");
					}
				}
				break;
			case INFLUENCE_OBJECT:
				//SYS INFLUENCE_OBJECT(Object target, float radius, int zero, int anti)
				//create [anti] influence on OBJECT [radius EXPRESSION]
				anti = params.get(3).intVal() != 0;
				if (anti) {
					line = "create anti influence on "+params.get(0).wrap(Priority.OBJECT)+" radius "+params.get(1).wrapExpression();
					return new Expression(line, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_INFLUENCE_RING");
				} else {
					line = "create influence on "+params.get(0).wrap(Priority.OBJECT)+" radius "+params.get(1).wrapExpression();
					return new Expression(line, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_INFLUENCE_RING");
				}
			case INFLUENCE_POSITION:
				//INFLUENCE_POSITION(Coord position, float radius, int zero, int anti)
				anti = params.get(3).intVal() != 0;
				if (anti) {
					//create anti influence at position COORD_EXPR [radius EXPRESSION]
					line = "create anti influence at position "+params.get(0).wrapCoordExpr()+" radius "+params.get(1).wrapExpression();
					return new Expression(line, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_INFLUENCE_RING");
				} else {
					//create influence at position COORD_EXPR [radius EXPRESSION]
					line = "create influence at "+params.get(0).wrapCoordExpr()+" radius "+params.get(1).wrapExpression();
					return new Expression(line, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_INFLUENCE_RING");
				}
			case CREATURE_CREATE_RELATIVE_TO_CREATURE:
				//CREATURE_CREATE_RELATIVE_TO_CREATURE(Object<SCRIPT_OBJECT_TYPE_CREATURE> model, float player, Coord pos, CREATURE_TYPE type, bool dumb)
				boolean dumb = params.get(4).boolVal();
				if (dumb) {
					//create dumb creature from creature OBJECT EXPRESSION at COORD_EXPR CREATURE_TYPE
					line = "create dumb creature from creature "+params.get(0).wrap(Priority.OBJECT)+" "+params.get(1).wrapExpression()+" at "+params.get(2).wrapCoordExpr()+" "+params.get(3).wrap(Priority.CONST_EXPR);
					return new Expression(line, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_CREATURE");
				} else {
					//create creature from creature OBJECT EXPRESSION at COORD_EXPR CREATURE_TYPE
					line = "create creature from creature "+params.get(0).wrap(Priority.OBJECT)+" "+params.get(1).wrapExpression()+" at "+params.get(2).wrapCoordExpr()+" "+params.get(3).wrap(Priority.CONST_EXPR);
					return new Expression(line, ArgType.OBJECT, "SCRIPT_OBJECT_TYPE_CREATURE");
				}
			case SNAPSHOT:
				params.set(2, null);	//implicit focus
				params.set(3, null);	//implicit position
				break;
			case UPDATE_SNAPSHOT_PICTURE:
				params.set(1, null);	//implicit focus
				params.set(2, null);	//implicit position
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
			Expression statement = decompile(func, symbol, params, null);
			return statement;
		}
		throw new DecompileException("Function "+func+" is not supported", currentScript, ip, instructions.get(ip));
		//IMPORTANT: going on may corrupt the stack!!!
	}
	
	private Expression decompile(NativeFunction func, Symbol symbol, List<Expression> params, ListIterator<Expression> paramIt) throws DecompileException {
		if (symbol.terminal && symbol.terminalType == TerminalType.KEYWORD) {
			return new Expression(symbol.keyword, func.returnType, func.returnClass);
		}
		boolean mainSymbol = paramIt == null;
		if (mainSymbol) {
			paramIt = params.listIterator();
		}
		List<Expression> statement = new LinkedList<>();
		String typeName = null;
		String subtype = null;
		int audioSfxIdIndex = -1;
		for (Symbol sym : symbol.expression) {
			skipNulls(paramIt);	//Skip implicit parameters
			if (sym.terminal) {
				if (sym.terminalType == TerminalType.KEYWORD) {
					statement.add(new Expression(sym.keyword));
				} else if (sym.terminalType != TerminalType.EOL) {
					Expression param = decompile(func, paramIt);
					statement.add(param);
				}
			} else if (sym.alternatives != null) {
				String dummy = dummyOptions.get(sym.keyword);
				if (dummy != null) {
					statement.add(new Expression(dummy));
				} else {
					String[] words = boolOptions.get(sym.keyword);
					if (words != null) {
						Expression param = paramIt.next();
						boolean val = param.boolVal();
						String opt = val ? words[0] : words[1];
						if (opt != null) {
							statement.add(new Expression(opt));
						}
					} else {
						words = enumOptions.get(sym.keyword);
						if (words != null) {
							Expression param = paramIt.next();
							int val = param.intVal();
							String opt = words[val];
							if (opt != null) {
								statement.add(new Expression(opt));
							}
						} else {
							Expression param = decompile(func, paramIt);
							statement.add(param);
						}
					}
				}
			} else if (sym.expression != null) {
				String[] words = boolOptions.get(sym.keyword);
				if (words != null) {
					Expression param = paramIt.next();
					boolean val = param.boolVal();
					String opt = val ? words[0] : words[1];
					if (opt != null) {
						statement.add(new Expression(opt));
					}
				} else {
					words = enumOptions.get(sym.keyword);
					if (words != null) {
						Expression param = paramIt.next();
						int val = param.intVal();
						String opt = words[val];
						if (opt != null) {
							statement.add(new Expression(opt));
						}
					} else if ("R_IDENTIFIER".equals(sym.keyword)) {
						statement.add(getLast(statement));
					} else {
						Argument arg = func.args[paramIt.nextIndex()];
						if (arg.type == ArgType.SCRIPT) {
							Expression param = paramIt.next();
							int strptr = param.intVal();
							String string = chl.data.getString(strptr);
							statement.add(new Expression(string));
						} else if (arg.type == ArgType.SCRIPT_OBJECT_TYPE) {
							Expression param = paramIt.next();
							subtype = null;
							if (param.isNumber()) {
								int val = param.intVal();
								typeName = getEnumEntry(ArgType.SCRIPT_OBJECT_TYPE, val);
								if (typeName != null) {
									typeName = coalesce(typeName);
									subtype = subtypes.get(typeName);
								}
								String alias = getSymbol(ArgType.SCRIPT_OBJECT_TYPE, val);
								statement.add(new Expression(alias));
							} else {
								statement.add(param);
							}
						} else if (arg.type == ArgType.SCRIPT_OBJECT_SUBTYPE) {
							Expression param = paramIt.next();
							if (param.isNumber() && param.intVal() != null) {
								int val = param.intVal();
								if (val == DEFAULT_SUBTYPE) {
									statement.add(null);
								} else {
									if (subtype == null) {
										Instruction instr = instructions.get(ip);
										warning("WARNING: subtype not defined for type "+typeName
												+" at instruction "+ip+" ("+instr+")"
												+" in script "+currentScript.getName()
												+" ("+currentScript.getSourceFilename()+":"+instr.lineNumber+")");
									}
									statement.add(getConstExpr(subtype, val));
								}
							} else {
								if (param.isVar()) {
									Var var = param.getVar();
									setVarType(var, arg.type, subtype);
								}
								statement.add(param);
							}
							subtype = null;
						} else if (arg.type == ArgType.CREATURE_ACTION_LEARNING_TYPE) {
							Expression param = paramIt.next();
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
							Expression param = paramIt.next();
							if (param.isNumber()) {
								int val = param.intVal();
								if (val == DEFAULT_SUBTYPE) {
									statement.add(null);
								} else {
									if (subtype == null) {
										statement.add(param);
										//
										Instruction instr = instructions.get(ip);
										warning("WARNING: subtype not defined for type "+typeName
												+" at instruction "+ip+" ("+instr+")"
												+" in script "+currentScript.getName()
												+" ("+currentScript.getSourceFilename()+":"+instr.lineNumber+")");
									} else {
										statement.add(getConstExpr(subtype, val));
									}
								}
							} else {
								if (param.isVar()) {
									Var var = param.getVar();
									setVarType(var, arg.type, subtype);
								}
								statement.add(param);
							}
							subtype = null;
						} else if (arg.type == ArgType.AUDIO_SFX_BANK_TYPE) {
							Expression param = paramIt.next();
							if (param.isNumber() && param.intVal() != null) {
								int val = param.intVal();
								Expression audioSfxId = statement.get(audioSfxIdIndex);
								if (audioSfxId.isNumber() && audioSfxId.intVal() != null) {
									String entry = getEnumEntry(ArgType.AUDIO_SFX_BANK_TYPE, val);
									subtype = subtypes.get(entry);
									statement.set(audioSfxIdIndex, getConstExpr(subtype, audioSfxId));
								}
								if (val == AUDIO_SFX_BANK_TYPE_IN_GAME) {
									statement.add(null);
								} else {
									statement.add(getConstExpr(arg.type, val));
								}
							} else {
								statement.add(param);
							}
							audioSfxIdIndex = -1;
						} else if (arg.type == ArgType.AUDIO_SFX_ID) {
							Expression param = paramIt.next();
							audioSfxIdIndex = statement.size();	//Delayed replacement
							statement.add(param);
						} else if ("[( PARAMETERS )]".equals(sym.keyword)) {
							Expression param = paramIt.next();
							if (!param.value.isEmpty()) {
								statement.add(new Expression("("+param+")"));
							}
						} else {
							Expression param = decompile(func, sym, params, paramIt);
							if (param == null) {
								//Don't add implicit parameters
							} else if (arg.type == ArgType.OBJECT && sym.optional
									&& sym.keyword.replace("OBJECT", "0").equals("["+param+"]")) {
								param = null;	//Don't add missing OBJECT
							} else if (arg.type == ArgType.COORD && sym.optional) {
								boolean withPosition = paramIt.next().boolVal();
								if (withPosition) {
									statement.add(param);
								} else {
									param = null;	//Don't add missing COORD_EXPR
								}
							} else if (arg.type == ArgType.BOOL && sym.optional) {
								throw new DecompileException("Undefined boolean option: "+sym.keyword, currentScript, ip, instructions.get(ip));
							} else if ((arg.type == ArgType.INT || arg.type.isEnum) && param.isNumber() && param.intVal() != null) {
								statement.add(getConstExpr(arg.type, param));
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
		//Build the statement to return
		if (statement.isEmpty()) {
			return null;
		} else if (statement.size() == 1) {
			return statement.get(0);
		}
		ListIterator<Expression> tokens = statement.listIterator();
		StringBuilder res = new StringBuilder(16 * statement.size());
		String prevToken = "(";
		while (tokens.hasNext()) {
			Expression part = tokens.next();
			if (part != null) {
				Priority priority = getPriority(part.type);
				String token = part.wrap(priority);
				if (!token.isEmpty()) {
					char c0 = token.charAt(0);
					char c1 = prevToken.charAt(prevToken.length() - 1);
					if (c0 != ']' && c0 != ')' && c0 != ','
							&& c1 != '[' && c1 != '(') {
						res.append(" ");
					}
					res.append(token);
					prevToken = token;
				}
			}
		}
		if (!mainSymbol) {
			Expression expr = new Expression(Priority.HIGHEST, res.toString());
			return expr;
		}
		Priority priority = func == NativeFunction.GET_POSITION ? Priority.ATOMIC : getPriority(func.returnType);
		Expression expr = new Expression(res.toString(), priority, func.returnType, func.returnClass);
		//Try to guess the specific return type
		Expression type, object;
		switch (func) {
			case CREATE:
			case CALL_NEAR:
			case CALL_IN:
			case CALL_IN_NEAR:
			case CALL_IN_NOT_NEAR:
			case CALL_POISONED_IN:
			case CALL_NOT_POISONED_IN:
			case CALL_NEAR_IN_STATE:
			case CALL_FLYING:
				type = params.get(0);
				if (type.isNumber()) {
					typeName = getEnumEntry(ArgType.SCRIPT_OBJECT_TYPE, type.intVal());
					typeName = coalesce(typeName);
					expr.type = new Type(ArgType.OBJECT, typeName);
				}
				break;
			case CREATE_WITH_ANGLE_AND_SCALE:
				type = params.get(2);
				if (type.isNumber()) {
					typeName = getEnumEntry(ArgType.SCRIPT_OBJECT_TYPE, type.intVal());
					typeName = coalesce(typeName);
					expr.type = new Type(ArgType.OBJECT, typeName);
				}
				break;
			case GAME_SUB_TYPE:
				object = params.get(0);
				if (object.type.isSpecificObject()) {
					expr.type = new Type(ArgType.SCRIPT_OBJECT_SUBTYPE, subtypes.get(object.type.specificType));
				}
				break;
			case RANDOM_ULONG:
				Expression val = params.get(0);
				expr.type = val.type;
				break;
			default:
		}
		return expr;
	}
	
	private Expression decompile(NativeFunction func, ListIterator<Expression> params) throws DecompileException {
		Argument arg = func.args[params.nextIndex()];
		Expression param = params.next();
		if (arg.type == ArgType.STRPTR && param.isNumber()) {
			int strptr = param.intVal();
			String string = chl.data.getString(strptr);
			return new Expression(escape(string));
		} else if (arg.type.isEnum && param.isNumber() && param.intVal() != null) {
			return getConstExpr(arg.type, param);
		} else if (arg.type == ArgType.INT && param.isNumber()) {
			if (func == NativeFunction.RANDOM_ULONG && !typeContextStack.isEmpty() && param.intVal() != null) {
				Type contextType = getLast(typeContextStack);
				return getConstExpr(contextType, param);
			} else if (!param.isExpression) {
				return getConstExpr(arg.type, param);
			}
		}
		return param;
	}
	
	private Expression getConstExpr(Type type, Expression expr) throws DecompileException {
		return getConstExpr(type.toString(), expr);
	}
	
	private Expression getConstExpr(ArgType type, Expression expr) throws DecompileException {
		return getConstExpr(type.keyword, expr);
	}
	
	private Expression getConstExpr(String type, Expression expr) throws DecompileException {
		if (expr.isNumber() && expr.intVal() != null) {
			int val = expr.intVal();
			Expression res = getConstExpr(type, val);
			res.type = expr.type;	//Overwrite with the original type
			return res;
		} else {
			throw new DecompileException("Expected immediate value", currentScript, ip, instructions.get(ip));
		}
	}
	
	private Expression getConstExpr(ArgType type, int val) {
		return getConstExpr(type.toString(), val);
	}
	
	private Expression getConstExpr(String type, int val) {
		String entry = getEnumEntry(type, val);
		if (entry != null) {
			String alias = aliases.getOrDefault(entry, entry);
			return new Expression(alias, Priority.ATOMIC, Type.INT, val);
		} else if (defineUnknownEnumsEnabled) {
			if (outputDisabled == 0) {
				requiredConstants.add(val);
			}
			return new Expression("UNK"+val, Priority.ATOMIC, Type.INT, val);
		} else {
			//Workaround for missing constants
			return new Expression("constant "+val, Priority.CONST_EXPR, Type.INT, val);
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
				Script script = chl.scripts.getScript(instr.intVal);
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
						argc = Utils.pop(argcStack);
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
				Script script = chl.scripts.getScript(instr.intVal);
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
				|| (instr.mode != flags && flags != -1)
				|| (instr.dataType != type && type != null)) {
			throw new DecompileException("Expected "+opcode, currentScript, index, instr);
		}
	}
	
	private void verify(int index, Instruction instr, NativeFunction func) throws DecompileException {
		verify(index, instr, OPCode.SYS, 1, null, func.ordinal());
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int mode, DataType type, int arg) throws DecompileException {
		if (instr.opcode != opcode
				|| (instr.mode != mode && mode != -1)
				|| (instr.dataType != type && type != null)
				|| instr.intVal != arg) {
			throw new DecompileException("Expected "+opcode, currentScript, index, instr);
		}
	}
	
	private void verify(int index, Instruction instr, OPCode opcode, int mode, DataType type, float arg) throws DecompileException {
		if (instr.opcode != opcode
				|| instr.mode != mode
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
	
	private Instruction find(OPCode opcode, int mode, DataType type, int arg) throws DecompileException {
		Instruction instr = next();
		while (instr.opcode != opcode || instr.mode != mode || instr.dataType != type || instr.intVal != arg) {
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
		return Utils.pop(stack);
	}
	
	private void decTabs() throws DecompileException {
		if (tabs.isEmpty()) {
			if (outputDisabled != 0) return;	//Ignore the error while doing heuristic steps
			throw new DecompileException("Cannot reduce indentation, already at column 1", currentScript, ip, instructions.get(ip));
		}
		tabs = tabs.substring(0, tabs.length() - 1);
		incTabs = false;
	}
	
	private VarWithIndex getVar(int id) throws InvalidVariableIdException {
		List<String> names;
		if (id > currentScript.getGlobalCount()) {
			id -= currentScript.getGlobalCount() + 1;
			names = currentScript.getVariables();
		} else {
			id--;
			names = chl.globalVars.getNames();
		}
		if (id < 0) {
			throw new InvalidVariableIdException(id);
		} else if (id >= names.size()) {
			warning("WARNING: "+id+" isn't a valid variable index, assuming "+(names.size() - 1)
					+" at "+currentScript.getSourceFilename()+":"+lineno);
			id = names.size() - 1;
		}
		String name = names.get(id);
		if ("LHVMA".equals(name)) {
			int index = 0;
			do {
				id--;
				index++;
				name = names.get(id);
			} while ("LHVMA".equals(name));
			return new VarWithIndex(getVar(name), index);
		} else {
			return new VarWithIndex(getVar(name));
		}
	}
	
	private Var getVar(String name) {
		Var var = localMap.get(name);
		if (var == null) {
			var = globalMap.get(name);
		}
		return var;
	}
	
	
	private static Priority getPriority(Type type) {
		if (type == null) {
			return Priority.VOID;
		}
		return getPriority(type.type);
	}
	
	private static Priority getPriority(ArgType type) {
		if (type != null) {
			if (type.isEnum) {
				return Priority.CONST_EXPR;
			} else {
				return priorityMap[type.ordinal()];
			}
		}
		return Priority.VOID;
	}
	
	/**Returns the generic of a type. For example CREATURE, FEMALE_CREATURE and DUMB_CREATURE are coalesced to CREATURE.
	 * @param typename
	 * @return the generic type
	 */
	private static String coalesce(String typename) {
		return coalesceTypes.getOrDefault(typename, typename);
	}
	
	private static void loadStatements() {
		int lineno = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(CHLDecompiler.class.getResourceAsStream(STATEMENTS_FILE)));) {
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
	
	
	private static class VarWithIndex {
		public final Var var;
		public int index;
		
		public VarWithIndex(Var var) {
			this(var, 0);
		}
		
		public VarWithIndex(Var var, int index) {
			this.var = var;
			this.index = index;
		}
		
		@Override
		public String toString() {
			if (var.isArray()) {
				return var.name+"["+index+"]";
			} else {
				return var.name;
			}
		}
	}
	
	
	private static class TempList<E> extends ArrayList<E> {
		private static final long serialVersionUID = 1L;
	}
}
