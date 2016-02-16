package simulizer.assembler.extractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import simulizer.assembler.extractor.problem.Problem;
import simulizer.assembler.extractor.problem.ProblemLogger;
import simulizer.assembler.representation.Instruction;
import simulizer.assembler.representation.Statement;
import simulizer.assembler.representation.Variable;
import simulizer.assembler.representation.operand.Operand;
import simulizer.assembler.representation.operand.OperandFormat;
import simulizer.parser.SimpBaseListener;
import simulizer.parser.SimpParser;

/**
 * extract the required information from a parse tree of a Simp program.
 * the data is gathered into publicly accessible fields.
 * @author mbway
 */
public class ProgramExtractor extends SimpBaseListener {

    private enum State {
        OUTSIDE,
        DATA_SEGMENT,
        TEXT_SEGMENT
    }

    private State currentState;
    ProblemLogger log;

    public Map<String, Integer> textSegmentLabels;
    public List<Statement> textSegment;

    public Map<String, Integer> dataSegmentLabels;
    public List<Variable> dataSegment;

    /**
     * keep track of labels that are waiting to be assigned to a line
     */
    public List<String> outstandingLabels;


    public ProgramExtractor(ProblemLogger log) {
        currentState = State.OUTSIDE;
        this.log = log;

        dataSegmentLabels = new HashMap<>();
        dataSegment = new ArrayList<>();
        textSegmentLabels = new HashMap<>();
        textSegment = new ArrayList<>();

        outstandingLabels = new ArrayList<>();
    }



    /**
     * when accessing a sub-rule from a grammar rule, the result may be null
     * or it may be non-null but with an exception showing the encountered error
     * @param ctx the context to check
     * @return whether there was a good parse for the grammar rule
     */
    private boolean goodMatch(ParserRuleContext ctx) {
        return (ctx != null) && (ctx.exception == null);
    }

    /**
     * whether a terminal node matches correctly
     * @param n the terminal node to test validity
     * @return whether the terminal node parsed correctly
     */
    private boolean goodMatch(TerminalNode n) {
        return n != null && !(n instanceof ErrorNode);
    }



    @Override
    public void visitErrorNode(ErrorNode node) {
        if(node.getSymbol().getCharPositionInLine() != -1) {
            int line = node.getSymbol().getLine();
            Interval i = node.getSourceInterval();
            int rangeStart = i.a;
            int rangeEnd = i.b;

            log.logProblem("Error node: \"" + node.getText() + "\"", line, rangeStart, rangeEnd);
        } else {
            log.logProblem("Error node with no position information: \"" + node.getText() + "\"", Problem.NO_LINE_NUM);
        }
    }

    @Override
    public void enterDataSegment(SimpParser.DataSegmentContext ctx) {
        currentState = State.DATA_SEGMENT;
    }
    @Override
    public void enterTextSegment(SimpParser.TextSegmentContext ctx) {
        currentState = State.TEXT_SEGMENT;
    }


    /**
     * check the operands of a .text or .data segment. They are allowed either
     * no operands, or a single address (which must be a positive integer)
     * @param ctx the operand list to check
     * @return whether the operand list meets the criteria
     */
    private boolean segmentDirectiveOperandsGood(SimpParser.DirectiveOperandListContext ctx) {
        if(ctx == null) {
            // no arguments is fine
            return true;
        } else {
            OperandExtractor ext = new OperandExtractor(log);
            List<Operand> operands = ext.extractDirectiveOperands(ctx);

            if(operands.size() != 1) {
                return false;
            } else {
                Operand op = operands.get(0);
                // should be an address, cannot be a label so must be an integer
                // integers aren't matched by the address rule, so check for
                // positive integer
                if(op.getType() != Operand.Type.Integer || op.asIntegerOp().value <= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void enterDataDirective(SimpParser.DataDirectiveContext ctx) {
        // ignored other than to log errors

        if(!goodMatch(ctx)) {
            log.logParseError("data directive", ctx);
            return;
        }

        if(!segmentDirectiveOperandsGood(ctx.directiveOperandList())) {
            log.logProblem("invalid operand(s) to .data directive. format: .data ADDRESS?", ctx);
        }
    }

    @Override
    public void enterTextDirective(SimpParser.TextDirectiveContext ctx) {
        // ignored other than to log errors

        if(!goodMatch(ctx)) {
            log.logParseError("text directive", ctx);
            return;
        }

        if(!segmentDirectiveOperandsGood(ctx.directiveOperandList())) {
            log.logProblem("invalid operand(s) to .text directive. format: .text ADDRESS?", ctx);
        }
    }

    @Override
    public void exitProgram(SimpParser.ProgramContext ctx) {
        if(!textSegmentLabels.containsKey("main")) {
            log.logProblem("The program has no 'main' label", Problem.NO_LINE_NUM);
        }
        if(!outstandingLabels.isEmpty()) {
            log.logProblem("These labels could not be assigned to addresses" +
                "because the end of the program was reached: \"" +
                String.join("\", \"", outstandingLabels) + "\"", Problem.NO_LINE_NUM);
        }
    }

    @Override
    public void enterLabel(SimpParser.LabelContext ctx) {

        if(!goodMatch(ctx)) {
            log.logParseError("label", ctx);
            return;
        }
        if(!goodMatch(ctx.labelID())) {
            log.logParseError("labelID", ctx.labelID());
            return;
        }

        String labelName = ctx.labelID().getText();

        if(textSegmentLabels.containsKey(labelName) || dataSegmentLabels.containsKey(labelName)) {
            log.logProblem("the label name: \"" + labelName + "\" is taken", ctx);
        } else if(currentState != State.TEXT_SEGMENT && labelName.equals("main")) {
            log.logProblem("The 'main' label must be inside the .text segment", ctx);
        }

        outstandingLabels.add(labelName);
    }

    @Override
    public void enterDirective(SimpParser.DirectiveContext ctx) {
        OperandExtractor ext = new OperandExtractor(log);
        List<Operand> operands = ext.extractDirectiveOperands(ctx.directiveOperandList());

        String directive = ctx.DIRECTIVE_ID().getText();


        if(currentState == State.DATA_SEGMENT) {
            switch(directive) {
                case ".globl":
                    // ignored other than to log errors
                    if(operands.isEmpty() || !operands.stream().allMatch(op ->
                        op.getType() == Operand.Type.Address &&
                        op.asAddressOp().labelOnly())) {
                        log.logProblem("invalid operand(s) to .globl directive. format: .globl LABEL(, LABEL)*", ctx);
                    }
                    break;
                case ".align":
                    // ignored other than to log errors
                    if(operands.size() != 1 || operands.get(0).getType() != Operand.Type.Integer) {
                        log.logProblem("invalid operand(s) to .align directive. format: .align INT", ctx);
                    }
                    break;
                case ".ascii":
                    if(operands.size() != 1 || !operands.stream().allMatch(op -> op.getType() == Operand.Type.String)) {
                        log.logProblem("invalid operand(s) to .ascii directive. format: .ascii STRING", ctx.directiveOperandList());
                    } else {
                        Operand op = operands.get(0); // only one argument permitted
                        pushVariable(new Variable(
                            Variable.Type.ASCII, op.asStringOp().value.length(), Optional.of(op), ctx.getStart().getLine()));
                    }
                    break;
                case ".asciiz":
                    if(operands.size() != 1 || !operands.stream().allMatch(op -> op.getType() == Operand.Type.String)) {
                        log.logProblem("invalid operand(s) to .asciiz directive. format: .asciiz STRING", ctx.directiveOperandList());
                    } else {
                        Operand op = operands.get(0); // only one argument permitted
                        op.asStringOp().value = op.asStringOp().value + '\0'; // add the null terminator

                        pushVariable(new Variable(
                            Variable.Type.ASCIIZ, op.asStringOp().value.length(), Optional.of(op), ctx.getStart().getLine()));
                    }
                    break;
                case ".byte":
                    if(operands.isEmpty() || !operands.stream().allMatch(op -> op.getType() == Operand.Type.Integer)) {
                        log.logProblem("invalid operand(s) to .byte directive. format: .byte INT(, INT)*", ctx.directiveOperandList());
                    } else {
                        for(Operand op : operands) {
                            pushVariable(new Variable(
                                Variable.Type.Byte, 1, Optional.of(op), ctx.getStart().getLine()));
                        }
                    }
                    break;
                case ".half":
                    if(operands.isEmpty() || !operands.stream().allMatch(op -> op.getType() == Operand.Type.Integer)) {
                        log.logProblem("invalid operand(s) to .half directive. format: .half INT(, INT)*", ctx.directiveOperandList());
                    } else {
                        for(Operand op : operands) {
                            pushVariable(new Variable(
                                Variable.Type.Half, 2, Optional.of(op), ctx.getStart().getLine()));
                        }
                    }
                    break;
                case ".word":
                    if(operands.isEmpty() || !operands.stream().allMatch(op -> op.getType() == Operand.Type.Integer)) {
                        log.logProblem("invalid operand(s) to .word directive. format: .word INT(, INT)*", ctx.directiveOperandList());
                    } else {
                        for(Operand op : operands) {
                            pushVariable(new Variable(
                                Variable.Type.Word, 4, Optional.of(op), ctx.getStart().getLine()));
                        }
                    }
                    break;

                case ".space":
                    if(operands.size() != 1 || operands.get(0).getType() != Operand.Type.Integer) {
                        log.logProblem("invalid operand(s) to .space directive. format: .space INT", ctx.directiveOperandList());
                    } else {
                        Operand op = operands.get(0);
                        pushVariable(new Variable(
                            Variable.Type.Space, op.asIntegerOp().value, Optional.empty(), ctx.getStart().getLine()));
                    }
                    break;
                default:
                    log.logParseError("Directive", ctx);
                    break;
            }
        } else if(currentState == State.TEXT_SEGMENT) {
            // noinspection StatementWithEmptyBody (intellij)
            if(directive.equals(".globl")) {
                // ignore this directive
            } else {
                log.logProblem("only .globl directives should be placed inside the .text segment", ctx);
            }
        } else {
            log.logProblem("Assembler directives should only be placed inside" +
                           "either the .data or .text segment", ctx);
        }
    }


    @Override
    public void enterStatement(SimpParser.StatementContext ctx) {
        if(currentState == State.TEXT_SEGMENT) {

            String instructionName = ctx.instruction().IDENTIFIER().getText();

            Instruction instruction;
            try {
                instruction = Instruction.fromString(instructionName);
            } catch(IllegalArgumentException e) {
                log.logProblem("Unknown instruction: \"" + instructionName + "\"", ctx.instruction());
                return;
            }

            OperandExtractor ext = new OperandExtractor(log);
            List<Operand> operands = ext.extractStatementOperands(ctx.statementOperandList());

            int requiredNum = instruction.getOperandFormat().getNumArgs();
            if(operands.size() != requiredNum) {
                log.logProblem("Wrong number of operands for " +
                    instructionName + " instruction (" + requiredNum + " required)", ctx.statementOperandList());
            } else {
                OperandFormat.OperandType arg1 = operands.size() > 0 ?
                    operands.get(0).getOperandFormatType() : null;

                OperandFormat.OperandType arg2 = operands.size() > 1 ?
                    operands.get(1).getOperandFormatType() : null;

                OperandFormat.OperandType arg3 = operands.size() > 2 ?
                    operands.get(2).getOperandFormatType() : null;

                if(!instruction.getOperandFormat().valid(arg1, arg2, arg3)) {
                    log.logProblem("Operands invalid for " + instructionName +
                        " instruction. Correct format: " + instruction.getOperandFormat().toString(),
                        ctx.statementOperandList());
                }
            }

            pushStatement(new Statement(instruction, operands, ctx.getStart().getLine()));

        } else {
            log.logProblem("Statements should only be placed inside the .text segment", ctx);
        }
    }


    public void pushStatement(Statement s) {
        textSegment.add(s);
        int index = textSegment.size() - 1;
        for(String labelName : outstandingLabels) {
            textSegmentLabels.put(labelName, index);
        }
        outstandingLabels.clear();
    }

    public void pushVariable(Variable v) {
        dataSegment.add(v);
        int index = dataSegment.size() - 1;
        for(String labelName : outstandingLabels) {
            dataSegmentLabels.put(labelName, index);
        }
        outstandingLabels.clear();
    }
}
