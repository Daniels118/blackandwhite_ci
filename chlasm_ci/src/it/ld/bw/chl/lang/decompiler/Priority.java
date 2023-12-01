package it.ld.bw.chl.lang.decompiler;

public enum Priority {
	VOID(0),
	
	OR0(2),
	OR(3),
	AND0(4),
	AND(5),
	NOT(6),
	CONDITION(7),
	
	ADD0(20),
	ADD(21),
	
	SUB(21),
	SUB2(22),
	NEG(22),
	
	MUL0(23),
	MUL(24),
	
	DIV(24),
	DIV2(25),
	MOD(24),
	MOD2(25),
	
	EXPRESSION(30),
	COORD_EXPR(30),
	
	CONST_EXPR(40),
	
	OBJECT(50),
	
	ATOMIC(99),
	;
	
	public final int value;
	
	private Priority(int value) {
		this.value = value;
	}
}
