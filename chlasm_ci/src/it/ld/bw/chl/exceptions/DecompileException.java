package it.ld.bw.chl.exceptions;

import it.ld.bw.chl.model.Instruction;
import it.ld.bw.chl.model.Script;

public class DecompileException extends Exception {
	private static final long serialVersionUID = 1L;
	
	private final Script script;
	private final int instructionAddress;
	private final Instruction instruction;
	
	public DecompileException(String msg) {
		super(msg);
		this.script = null;
		this.instructionAddress = -1;
		this.instruction = null;
	}
	
	public DecompileException(String msg, Script script) {
		super(msg+" in script "+script.getName()+" ("+script.getSourceFilename()+")");
		this.script = script;
		this.instructionAddress = -1;
		this.instruction = null;
	}
	
	public DecompileException(String msg, Script script, int instructionAddress) {
		super(msg+" at instruction "+instructionAddress+" in script "+script.getName()
				+" ("+script.getSourceFilename()+")");
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = null;
	}
	
	public DecompileException(String msg, Script script, int instructionAddress, Instruction instruction) {
		super(msg+" at instruction "+instructionAddress+" ("+instruction+") in script "+script.getName()
				+" ("+script.getSourceFilename()+":"+instruction.lineNumber+")");
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = instruction;
	}
	
	public DecompileException(Script script, int instructionAddress, Exception cause) {
		super(cause.getMessage()+" at instruction "+instructionAddress+" in script "+script.getName()
				+" ("+script.getSourceFilename()+")", cause);
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = null;
	}
	
	public DecompileException(Script script, int instructionAddress, Instruction instruction, Exception cause) {
		super(cause.getMessage()+" at instruction "+instructionAddress+" ("+instruction+") in script "+script.getName()
				+" ("+script.getSourceFilename()+":"+instruction.lineNumber+")", cause);
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = instruction;
	}
	
	public Script getScript() {
		return script;
	}
	
	public int getInstructionAddress() {
		return instructionAddress;
	}
	
	public Instruction getInstruction() {
		return instruction;
	}
}
