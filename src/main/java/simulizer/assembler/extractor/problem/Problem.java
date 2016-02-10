package simulizer.assembler.extractor.problem;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;

public class Problem {
    public String message;

    // if the problem is localised to a line in the source
    // then this is populated, otherwise -1
    public int lineNum;
    public static int NO_LINE_NUM = -1;

    // if the problem is localised to a range of characters,
    // these attributes are populated. If not they are both -1.
    // The index is absolute to the whole program string.
    public int rangeStart;
    public int rangeEnd;

    /**
     * this is private because one might forget to give context. If they actually mean to
     * state the the program has no associated line number, use:
     * new Problem("message", Problem.NO_LINE_NUM);
     */
    private Problem(String message) {
        this.message = message;
    }

    public Problem(String message, int lineNum) {
        this.message = message;
        this.lineNum = lineNum;
        this.rangeStart = -1;
        this.rangeEnd = -1;
    }

    public Problem(String message, int lineNum, int rangeStart, int rangeEnd) {
        this.message = message;
        this.lineNum = lineNum;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
    }

    /**
     * create a problem which spans the entire context (grammar rule) passed to it
     * @param message a description of the problem
     * @param ctx the context (grammar rule) which the problem pertains to.
     */
    public Problem(String message, ParserRuleContext ctx) {
        this.message = message;

        lineNum = ctx.getStart().getLine();

        Interval i = ctx.getSourceInterval();
        rangeStart = i.a;
        rangeEnd = i.b;
    }
}
