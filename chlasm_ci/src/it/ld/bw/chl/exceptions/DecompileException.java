package it.ld.bw.chl.exceptions;

import it.ld.bw.chl.model.Instruction;

public class DecompileException extends Exception {
	private static final long serialVersionUID = 1L;
	
	private final String script;
	private final int instructionAddress;
	private final Instruction instruction;
	
	public DecompileException(String msg) {
		super(msg);
		this.script = null;
		this.instructionAddress = -1;
		this.instruction = null;
	}
	
	public DecompileException(String msg, String script, int instructionAddress) {
		super(msg+" at instruction "+instructionAddress+" in script "+script);
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = null;
	}
	
	public DecompileException(String msg, String script, int instructionAddress, Instruction instruction) {
		super(msg+" at instruction "+instructionAddress+" ("+instruction+") in script "+script);
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = instruction;
	}
	
	public DecompileException(String script, int instructionAddress, Exception cause) {
		super(cause.getMessage()+" at instruction "+instructionAddress+" in script "+script, cause);
		this.script = script;
		this.instructionAddress = instructionAddress;
		this.instruction = null;
	}
	
	public String getScript() {
		return script;
	}
	
	public int getInstructionAddress() {
		return instructionAddress;
	}
	
	public Instruction getInstruction() {
		return instruction;
	}
}
