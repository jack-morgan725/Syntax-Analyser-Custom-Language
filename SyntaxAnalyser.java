
import java.io.* ;
import java.util.ArrayList;

/**
 * Checks if a program is syntactically correct using the grammar supplied in the coursework assignment.
 * @Author Jack Morgan
 */
public class SyntaxAnalyser extends AbstractSyntaxAnalyser
{
	// Stack of temporary variable stacks for nested blocks. Used to keep track of temporary variables and their scope in the symbol table.
	private ArrayList<ArrayList<Variable>> temporaryVariableStack = new ArrayList<>();

	// Stack of global variables. Used to check if a variable is temporary or not.
	private ArrayList<Variable> globalVariableStack = new ArrayList<>();

	// Used to prevent illegal string operations.
	private boolean stringNotAllowedInThisExpression = false;
	private boolean stringInExpression = false;

	// Used to check if a symbol table should be generated for temporary variables encountered.
	private boolean insideForLoop = false;
	private int forLoopDepth = 0;

	// A stack consisting of all non-finished non-terminals. Used to output stack trace if an error occurs.
	private ArrayList<NonterminalStackMember> stackTrace = new ArrayList<>();

	/**
	 * A non-terminal stack member is added to the stackTrace and removed once the non-terminal ends.
	 * If an error occurs the contents of stackTrace can be used to output a stack trace for the error.
	 */
	private class NonterminalStackMember
	{
		public String nonterminalName;
		public int nonterminalLineNumber;

		/**
		 * Constructor for non-terminal stack members.
		 * @param nonterminalName the name of the non-terminal.
		 * @param nonterminalLineNumber the line number the non-terminal is located at.
		 */
		public NonterminalStackMember(String nonterminalName, int nonterminalLineNumber)
		{
			this.nonterminalName = nonterminalName;
			this.nonterminalLineNumber = nonterminalLineNumber;
		}
	}

	/**
	 * Instantiates a lexical analyser to generate the tokens for a program file.
	 * @param filename the program file to generate the tokens for.
	 * @throws IOException
	 */
	public SyntaxAnalyser(String filename) throws IOException
	{
		lex = new LexicalAnalyser(filename);
	}

	/**
	 * The initial method called when analysing a file. Checks if start and end tokens are present, and
	 * starts the recursive check for the contents of the program file.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _statementPart_() throws IOException, CompilationException
	{
		myGenerate.commenceNonterminal("StatementPart");
		stackTrace.add(new NonterminalStackMember("Statement Part", nextToken.lineNumber));

		try
		{
			acceptTerminal(Token.beginSymbol);

			// Start the sequence of recursive calls to check the program file's syntax. If we return from this method then all program code was valid.
			_statementList_();

			// Check for terminating end symbol.
			acceptTerminal(Token.endSymbol);
		}
		catch (CompilationException e)
		{
			// Catch the compilation exception thrown when the parser detects an error and output the trace of non-terminals that lead to the error.
			for(int i = stackTrace.size()-1; i >= 0; i--)
				System.out.println(">	Caused by " + stackTrace.get(i).nonterminalName + " on line " + stackTrace.get(i).nonterminalLineNumber);
			System.out.printf("-------------------------------------------------------------------------------------------------------");
			System.out.printf("------------------------------------------------------------------------------------------------------->%n");

			// Throw the exception to the parser method so that we can process the next file.
			throw e;
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("StatementPart");
	}

	/**
	 * Method for statement list non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _statementList_() throws IOException, CompilationException
	{
		myGenerate.commenceNonterminal("StatementList");
		stackTrace.add(new NonterminalStackMember("Statement List", nextToken.lineNumber));

		/** Statement reached. Method called to check its validity. **/
		_statement_();

		/** If a semi-colon exists after the statement then another statement should follow.
		 *  Accept the semi-colon and call statement list recursively to check the next statement. **/
		if (nextToken.symbol == Token.semicolonSymbol)
		{
			acceptTerminal(Token.semicolonSymbol);
			_statementList_();
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("StatementList");
	}

	/**
	 * Method for statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _statement_() throws IOException, CompilationException 
	{
		/**
		 * A statement can be one of six types:
		 * 1. Assignment.
		 * 2. If.
		 * 3. While.
		 * 4. Procedure.
		 * 5. Until.
		 * 6. For.
		 * Each statement type has a unique starting token. We can use this to determine which method to call to
		 * check if the statement is formed correctly. If the current symbol doesn't match any of these then we
		 * can report this as a syntax error.
		 */
		myGenerate.commenceNonterminal("Statement");
		stackTrace.add(new NonterminalStackMember("Statement", nextToken.lineNumber));

		switch(nextToken.symbol) 
		{
			case(Token.identifier):  { _assignmentStatement_(); break; }
			case(Token.ifSymbol):    { _ifStatement_();         break; }
			case(Token.whileSymbol): { _whileStatement_();      break; }
			case(Token.callSymbol):  { _procedureStatement_();  break; }
			case(Token.untilSymbol): { _untilStatement_();      break; }
			case(Token.forSymbol):   { _forStatement_();        break; }
			default: myGenerate.reportError(nextToken, "Unexpected symbol. A token is either incorrectly formed, misplaced, or missing.");
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("Statement");
	}

	/**
	 * Method for assignment statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _assignmentStatement_() throws IOException, CompilationException 
	{
		myGenerate.commenceNonterminal("AssignmentStatement");
		stackTrace.add(new NonterminalStackMember("Assignment Statement", nextToken.lineNumber));

		String identifier = nextToken.text;
		acceptTerminal(Token.identifier);
		acceptTerminal(Token.becomesSymbol);

		/**
		 * Assignment statements can end in either a string constant or expression.
		 * If the next symbol is a string constant. Accept it, add the variable to the symbol table, and return.
		 * A variable is only added if it hasn't already been declared.
		 */
		if (nextToken.symbol == Token.stringConstant)
		{
			acceptTerminal(Token.stringConstant);
			addVariable(identifier, Variable.Type.STRING);
		}
		else
		{
			_expression_();
			addVariable(identifier, Variable.Type.NUMBER);
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("AssignmentStatement");
	}

	/**
	 * Adds variables to the symbol table and manages temporary and global variable stacks.
	 * @param identifier the name of the variable encountered.
	 * @param type the type of the variable encountered.
	 */
	public void addVariable(String identifier, Variable.Type type)
	{
		myGenerate.addVariable(new Variable(identifier, type));

		// If we're not in a for block -> also add the variable to the global stack.
		if (!insideForLoop)
			globalVariableStack.add(new Variable(identifier, type));

		// However, if we're in a for block ->
		if (insideForLoop)
		{
			// Check if we've entered a new for loop -> generate a temporary symbol table for it if we have.
			if (temporaryVariableStack.size() != forLoopDepth)
				temporaryVariableStack.add(new ArrayList<>());

			// Check if the variable exists outside of the scope of the for block or has already been declared in the for block.
			boolean alreadyExists = isFreshTemporaryVariable(identifier);

			// If it doesn't exist outside the scope of the block or hasn't already been declared -> add it to the temporary stack.
			if (!alreadyExists)
				temporaryVariableStack.get(temporaryVariableStack.size()-1).add(new Variable(identifier, type));
		}
	}

	/**
	 * Checks global symbol table and temporary variable stack to see if a variable already exists.
	 * @param identifier the identifier of the variable to check for.
	 * @return true if the variable was not found or false if it was found.
	 */
	public boolean isFreshTemporaryVariable(String identifier)
	{
		boolean alreadyExists = false;

		// Check this block's symbol stack.
		for (Variable v : temporaryVariableStack.get(temporaryVariableStack.size()-1))
			if (v.identifier.equals(identifier))
				alreadyExists = true;

		// Check the global stack.
		for (Variable v : globalVariableStack)
			if (v.identifier.equals(identifier))
				alreadyExists = true;

		return alreadyExists;
	}

	/**
	 * Method for if statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _ifStatement_() throws IOException, CompilationException  
	{
		myGenerate.commenceNonterminal("IfStatement");
		stackTrace.add(new NonterminalStackMember("If Statement", nextToken.lineNumber));

		acceptTerminal(Token.ifSymbol);
		_condition_();

		acceptTerminal(Token.thenSymbol);
		_statementList_();

		/**
		 * If statements can branch in two directions. If the current token is a endSymbol, then the statement
		 * is finished, the symbols can be accepted, and we can return. Else we must check if an else symbol.
		 */
		if (nextToken.symbol != Token.endSymbol)
		{
			acceptTerminal(Token.elseSymbol);
			_statementList_();
		}

		if (nextToken.symbol == Token.endSymbol)
		{
			acceptTerminal(Token.endSymbol);
			acceptTerminal(Token.ifSymbol);
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("IfStatement");
	}

	/**
	 * Method for while statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _whileStatement_() throws IOException, CompilationException  
	{
		myGenerate.commenceNonterminal("WhileStatement");
		stackTrace.add(new NonterminalStackMember("While Statement", nextToken.lineNumber));

		acceptTerminal(Token.whileSymbol);
		_condition_();

		acceptTerminal(Token.loopSymbol);
		_statementList_();

		acceptTerminal(Token.endSymbol);
		acceptTerminal(Token.loopSymbol);

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("WhileStatement");
	}

	/**
	 * Method for procedure statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _procedureStatement_() throws IOException, CompilationException 
	{
		myGenerate.commenceNonterminal("ProcedureStatement");
		stackTrace.add(new NonterminalStackMember("Procedure Statement", nextToken.lineNumber));

		acceptTerminal(Token.callSymbol);
		acceptTerminal(Token.identifier);
		acceptTerminal(Token.leftParenthesis);

		_argumentList_();
		acceptTerminal(Token.rightParenthesis);

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("ProcedureStatement");
	}

	/**
	 * Method for until statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _untilStatement_() throws IOException, CompilationException  
	{
		myGenerate.commenceNonterminal("UntilStatement");
		stackTrace.add(new NonterminalStackMember("Until Statement", nextToken.lineNumber));

		acceptTerminal(Token.doSymbol);
		_statementList_();

		acceptTerminal(Token.untilSymbol);
		_condition_();

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("UntilStatement");
	}

	/**
	 * Method for for statement non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _forStatement_() throws IOException, CompilationException 
	{
		myGenerate.commenceNonterminal("ForStatement");
		stackTrace.add(new NonterminalStackMember("For Statement", nextToken.lineNumber));

		insideForLoop = true;
		forLoopDepth += 1;

		acceptTerminal(Token.forSymbol);
		acceptTerminal(Token.leftParenthesis);

		_assignmentStatement_();
		acceptTerminal(Token.semicolonSymbol);

		_condition_();
		acceptTerminal(Token.semicolonSymbol);

		_assignmentStatement_();
		acceptTerminal(Token.rightParenthesis);
		acceptTerminal(Token.doSymbol);

		_statementList_();
		acceptTerminal(Token.endSymbol);
		acceptTerminal(Token.loopSymbol);

		forLoopDepth -= 1;

		// Remove all temporary variables for this for loop from the symbol table.
		for (Variable v : temporaryVariableStack.get(temporaryVariableStack.size()-1))
			myGenerate.removeVariable(v);

		// Remove the stack frame for this for loop.
		temporaryVariableStack.remove(temporaryVariableStack.size()-1);

		// If depth is 0 then we're no longer in a for loop.
		if (forLoopDepth == 0)
			insideForLoop = false;

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("ForStatement");
	}

	/**
	 * Method for argument list non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _argumentList_() throws IOException, CompilationException 
	{
		myGenerate.commenceNonterminal("ArgumentList");
		stackTrace.add(new NonterminalStackMember("Argument List", nextToken.lineNumber));
		acceptTerminal(Token.identifier);

		/**
		 * If an identifier is followed by a comma then another argument should exists. Therefore, argument list is
		 * called again recursively.
		 */
		if (nextToken.symbol == Token.commaSymbol)
		{
			acceptTerminal(Token.commaSymbol);
			_argumentList_();
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("ArgumentList");
	}

	/**
	 * Method for condition non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _condition_() throws IOException, CompilationException 
	{
		myGenerate.commenceNonterminal("Condition");
		stackTrace.add(new NonterminalStackMember("Condition", nextToken.lineNumber));
		acceptTerminal(Token.identifier);
		_conditionalOperator_();

		/**
		 * A condition can end in one of three symbols. If a the next symbol does not end in one of these symbols
		 * then we can report this as a syntax error.
		 */
 		switch(nextToken.symbol)
		{
			case(Token.identifier):     { acceptTerminal(Token.identifier);     break; }
			case(Token.numberConstant): { acceptTerminal(Token.numberConstant); break; }
			case(Token.stringConstant): { acceptTerminal(Token.stringConstant); break; }
			default: myGenerate.reportError(nextToken, "Unexpected symbol. A token is either incorrectly formed, misplaced, or missing.");
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("Condition");
	}

	/**
	 * Method for conditional operator non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _conditionalOperator_() throws IOException, CompilationException 
	{
		myGenerate.commenceNonterminal("ConditionalOperator");
		stackTrace.add(new NonterminalStackMember("Conditional Operator", nextToken.lineNumber));

		/**
		 * A conditional operator can be one of six symbols. If the next symbol is not one of these symbols
		 * then we can report this as a syntax error.
		 */
		switch (nextToken.symbol)
		{
			case(Token.greaterThanSymbol):  { acceptTerminal(Token.greaterThanSymbol);  break; }
			case(Token.greaterEqualSymbol): { acceptTerminal(Token.greaterEqualSymbol); break; }
			case(Token.equalSymbol):        { acceptTerminal(Token.equalSymbol);        break; }
			case(Token.notEqualSymbol):     { acceptTerminal(Token.notEqualSymbol);     break; }
			case(Token.lessThanSymbol):     { acceptTerminal(Token.lessThanSymbol);     break; }
			case(Token.lessEqualSymbol):    { acceptTerminal(Token.lessEqualSymbol);    break; }
			default: myGenerate.reportError(nextToken, "Unexpected symbol. A token is either incorrectly formed, misplaced, or missing.");
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("ConditionalOperator");
	}

	/**
	 * Method for expression non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _expression_() throws IOException, CompilationException  
	{
		myGenerate.commenceNonterminal("Expression");
		stackTrace.add(new NonterminalStackMember("Expression", nextToken.lineNumber));

		/** Each expression must start with a term. **/
		_term_();

		/**
		 * If the next symbol is a '+' or '-' then another expression should follow. Therefore, we can accept the
		 * symbol and call expression again recursively.
		 */
		if (nextToken.symbol == Token.plusSymbol)
		{
			acceptTerminal(Token.plusSymbol);
			_expression_();
		}
		else if (nextToken.symbol == Token.minusSymbol)
			checkForIllegalString("-", Token.minusSymbol, "Expression");

		stringNotAllowedInThisExpression = false;

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("Expression");
	}

	/**
	 * Method for term non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _term_() throws IOException, CompilationException
	{
		myGenerate.commenceNonterminal("Term");
		stackTrace.add(new NonterminalStackMember("Term", nextToken.lineNumber));

		/** Each term must start with a factor. **/
		_factor_();

		/** If the following symbol is a '*' or '/' then another term should follow. Therefore, we can accept
		 * the symbol and call expression again recursively. **/
		if (nextToken.symbol == Token.timesSymbol)
			checkForIllegalString("*", Token.timesSymbol, "Term");

		else if (nextToken.symbol == Token.divideSymbol)
			checkForIllegalString("/", Token.divideSymbol, "Term");

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("Term");
	}

	/**
	 * Method called when a '/', '*', or '-' is detected in an expression. If a string is already in the expression then
	 * a CompilationException is thrown.
	 * @param operator the operator added to the expression.
	 * @param symbol the symbol (operator) to accept.
	 * @param nonterminalName the non-terminal to call.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void checkForIllegalString(String operator, int symbol, String nonterminalName) throws IOException, CompilationException
	{
		// If a String is already in the expression then report an illegal String operation.
		if (stringInExpression)
			myGenerate.reportError(nextToken, "Illegal String operation. The '" + operator + "' operator cannot used used with String types.");

		// Else declare that a string is not allowed the in expression as it now contains incompatible operators.
		stringNotAllowedInThisExpression = true;
		acceptTerminal(symbol);

		switch(nonterminalName)
		{
			case "Term":       { _term_();       break; }
			case "Expression": { _expression_(); break; }
		}
	}

	/**
	 * Method for factor non-terminal.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void _factor_() throws IOException, CompilationException
	{
		myGenerate.commenceNonterminal("Factor");
		stackTrace.add(new NonterminalStackMember("Factor", nextToken.lineNumber));

		switch(nextToken.symbol)
		{
			// A factor must be start with one of the three following terminals. If it does not then we can report a syntax error.
			case(Token.identifier):
			{
				// Checking if identifier is in the symbol table. Must be in the symbol table to use it. If it is not we can report an error.
				if (myGenerate.getVariable(nextToken.text) != null)
				{
					// Checking if identifier is a string and if it is - is it legal in the current expression.
					if (myGenerate.getVariable(nextToken.text).type == Variable.Type.STRING)
					{
						stringInExpression = true;
						if (stringNotAllowedInThisExpression)
							myGenerate.reportError(nextToken, "Illegal String operation. String types are not compatible with number types or '/', '-'. and '*' operators.");
					}
					acceptTerminal(Token.identifier);
				}
				else
					myGenerate.reportError(nextToken, "Uninitialised variable. A variable must first be initialised before it can be used.");
				break;
			}
			case(Token.numberConstant): { acceptTerminal(Token.numberConstant); break; }
			case(Token.leftParenthesis):
			{
				acceptTerminal(Token.leftParenthesis);
				_expression_();
				acceptTerminal(Token.rightParenthesis);
				break;
			}
			default: { myGenerate.reportError(nextToken, "Unexpected symbol. A token is either incorrectly formed, misplaced, or missing."); }
		}

		stackTrace.remove(stackTrace.size()-1);
		myGenerate.finishNonterminal("Factor");
	}

	/**
	 * Checks if the supplied symbol is what we expect. If it is then the next token is collected. If it is not
	 * then an error is generated.
	 * @param symbol the symbol to be checked against the current token.
	 * @throws IOException
	 * @throws CompilationException
	 */
	public void acceptTerminal(int symbol) throws IOException, CompilationException 
	{
		if (nextToken.symbol == symbol) 
		{
			myGenerate.insertTerminal(nextToken);
			nextToken = lex.getNextToken(); 
		} 
		else
			myGenerate.reportError(nextToken, "Unexpected symbol. A token is either incorrectly formed, misplaced, or missing.");
	}
}









