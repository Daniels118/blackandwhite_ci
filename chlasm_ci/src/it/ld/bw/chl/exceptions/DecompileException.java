package it.ld.bw.chl.exceptions;

public class DecompileException extends Exception {
	private static final long serialVersionUID = 1L;
	
	private final String script;
	private final int instructionAddress;
	
	public DecompileException(String msg) {
		super(msg);
		this.script = null;
		this.instructionAddress = -1;
	}
	
	public DecompileException(String msg, String script, int instructionAddress) {
		super(msg+" at instruction "+instructionAddress+" in script "+script);
		this.script = script;
		this.instructionAddress = instructionAddress;
	}
	
	public DecompileException(String script, int instructionAddress, Exception cause) {
		super(cause.getMessage()+" at instruction "+instructionAddress+" in script "+script, cause);
		this.script = script;
		this.instructionAddress = instructionAddress;
	}
	
	public String getScript() {
		return script;
	}
	
	public int getInstructionAddress() {
		return instructionAddress;
	}
}
