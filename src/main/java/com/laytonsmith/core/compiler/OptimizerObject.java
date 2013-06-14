package com.laytonsmith.core.compiler;

import com.laytonsmith.core.CHLog;
import com.laytonsmith.core.compiler.Optimizable.OptimizationOption;
import com.laytonsmith.core.ParseTree;
import com.laytonsmith.core.Procedure;
import com.laytonsmith.core.constructs.CFunction;
import com.laytonsmith.core.constructs.CVoid;
import com.laytonsmith.core.constructs.IVariable;
import com.laytonsmith.core.constructs.Target;
import com.laytonsmith.core.environments.Environment;
import com.laytonsmith.core.exceptions.ConfigCompileException;
import com.laytonsmith.core.exceptions.ConfigRuntimeException;
import com.laytonsmith.core.functions.DataHandling;
import com.laytonsmith.core.functions.EventBinding;
import com.laytonsmith.core.functions.Function;
import com.laytonsmith.core.functions.FunctionList;
import com.laytonsmith.core.functions.StringHandling;
import com.laytonsmith.core.natives.interfaces.Mixed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 *
 */
class OptimizerObject {

	private final static EnumSet<Optimizable.OptimizationOption> NO_OPTIMIZATIONS = EnumSet.noneOf(Optimizable.OptimizationOption.class);
	private ParseTree root;
	private Environment env;
	
	private final static String __autoconcat__ = new CompilerFunctions.__autoconcat__().getName();
	private final static String cc = new StringHandling.cc().getName();
	private final static String assign = new DataHandling.assign().getName();
	private final static String proc = new DataHandling.proc().getName();
	private final static String bind = new EventBinding.bind().getName();
	

	public OptimizerObject(ParseTree root, Environment compilerEnvironment) {
		this.root = root;
		env = compilerEnvironment;
	}

	/**
	 * Optimizes the ParseTree. There are several stages to the optimization, some
	 * of them aren't true optimizations, rather are part of the compilation process,
	 * but generally act like optimizations, so are included here. The lexer and compiler
	 * will have already run by the time this method is run, so we start with an unoptimized
	 * parse tree, with __autoconcat__s in place. The general process is this:
	 * <ul>
	 * <li>__autoconcat__ reduction - Remove all the references to __autoconcat__ in the parse tree.
	 * This will ensure that everything is functional at this point, and all operators will have been
	 * removed.</li>
	 * <li>Turing reduction - Optimize the branching turing methods, for instance if and for. This has the potential
	 * of actually removing branches of code, so this can be used as a preprocessor, since only loose linking is
	 * done during this stage (TODO). This means that if a function is only found in one platform, it can be fully
	 * removed at this stage, before platform linking occurs. Loose linking simply means that all platforms are
	 * considered valid at this point. Once this stage is complete, it is a compiler error to not have fully
	 * removed all functions not for this platform. Turing functions are inherently platform independent for this
	 * reason.</li>
	 * <li>Pre-proc function optimization/tight linking - Functions are optimized now given their current arguments.
	 * Some arguments will be dynamic due to procs though, so the optimization won't catch everything if the proc
	 * turns out to be a constexpr later, so we have to rerun in a bit, but all the major optimizations should run
	 * now, and it may reorganize some of the AST so that proc linking is easier later, and constexpr evaluation
	 * is possible for more procs. Functions are also tightly
	 * linked at this point to the target platform.</li>
	 * <li>Strict mode checks - Check for violations of strict mode, as well as type safety where
	 * applicable.</li>
	 * <li>Proc optimization - constexpr procs can be identified and fulfilled at this point, and completely reduced
	 * in the case that it is possible. Proc linking also takes place here, to verify that procs are properly scoped.</li>
	 * <li>Post-proc function optimization - Functions can be re-optimized at this point, as constexpr procs may
	 * have been fully reduced, which will cause some of the previously dynamic arguments to be constant.</li>
	 * <li>Post-proc function optimization for function pullups - The last reduction may again have 
	 * caused the previously dynamic functions to become constant, and caused certain pullups to be possible again,
	 * though the functions themselves won't re-optimize at this point.</li>
	 * <li>Turing reduction - Again, we turing reduce, since so many things that were dynamic before may now be
	 * constant, and we can reduce the tree further.</li>
	 * </ul>
	 * 
	 * In the future, the following optimizations would be added after this point:
	 * <ul>
	 * <li>Compiler Annotations - Build time annotations are processed at this point. They are given
	 * a copy of the element they are tagged to, and they can do compile time processing, where applicable.</li>
	 * </ul>
	 * @return The optimized parse tree.
	 * @throws ConfigCompileException 
	 */
	public ParseTree optimize() throws ConfigCompileException {
		//__autoconcat__ reduction
		optimize01(root, env);
		//turing reduction/TODO: loose linking
		optimize02(root, env, false);
		env.getEnv(CompilerEnvironment.class).pushProcedureScope();
		try{
			//pre-proc function optimization/tight linking
			optimize03(root, false); // Once
		} catch(PullMeUpException e){
			//If at any point we get a PullMeUpException, we just set root = to that node
			root = e.getNode();
		}
		env.getEnv(CompilerEnvironment.class).popProcedureScope();
		//strict mode checks
		optimize04(root, env, new ArrayList<String>());
		//proc optimization
		optimize05(root, env);
		env.getEnv(CompilerEnvironment.class).pushProcedureScope();
		try{
			//post-proc function optimization
			optimize03(root, true); // Twice
		} catch(PullMeUpException e){
			root = e.getNode();
		}
		env.getEnv(CompilerEnvironment.class).popProcedureScope();
		env.getEnv(CompilerEnvironment.class).pushProcedureScope();
		try{
			//post-proc function optimization for function pull ups
			optimize03(root, true); // Thrice
		} catch(PullMeUpException e){
			root = e.getNode();
		}
		env.getEnv(CompilerEnvironment.class).popProcedureScope();
		//turing reduction again
		optimize02(root, env, true); // Gotta do this one again too
		
		return root;
	}

	/**
	 * This optimization level removes all the __autoconcat__s (and
	 * inadvertently several other constructs as well)
	 *
	 * @param tree
	 * @param compilerEnvironment
	 * @throws ConfigCompileException
	 */
	private void optimize01(ParseTree tree, Environment compilerEnvironment) throws ConfigCompileException {
		if (tree.getData() instanceof CFunction && tree.getData().val().equals(cc)) {
			for (int i = 0; i < tree.getChildren().size(); i++) {
				ParseTree node = tree.getChildAt(i);
				if (node.getData().val().equals(__autoconcat__)) {
					ParseTree tempNode = CompilerFunctions.__autoconcat__.optimizeSpecial(node.getChildren(), false);
					tree.setData(tempNode.getData());
					tree.setChildren(tempNode.getChildren());
					optimize01(tree, compilerEnvironment);
					return;
				}
			}
		} else {
			if (tree.getData() instanceof CFunction && tree.getData().val().equals(__autoconcat__)) {
				ParseTree tempNode = CompilerFunctions.__autoconcat__.optimizeSpecial(tree.getChildren(), true);
				tree.setData(tempNode.getData());
				tree.setChildren(tempNode.getChildren());
			}
			for (int i = 0; i < tree.getChildren().size(); i++) {
				ParseTree node = tree.getChildren().get(i);
				optimize01(node, compilerEnvironment);
			}
		}
	}

	/**
	 * This pass optimizes all turing functions. That is, branch functions like
	 * if, and for. It also checks for unreachable code, and removes it, along
	 * with issuing a warning. Boolean simplification also happens during this step,
	 * so things like {@code if(@i == 4 && @i == 5)} can be removed.
	 *
	 * @param tree
	 * @param compilerEnvironment
	 */
	private void optimize02(ParseTree tree, Environment compilerEnvironment, boolean optimizeProcs) throws ConfigCompileException {
		ParseTree tempNode = null;
		int numChildren;
		do {
			numChildren = tree.numberOfChildren();
			if (tree.getData() instanceof CFunction) {
				if(((CFunction)tree.getData()).isProcedure()){
					//Procedure. Can't optimize this yet, so just return.
					return;
				}
				Function func = ((CFunction) tree.getData()).getFunction();
				List<ParseTree> children = tree.getChildren();
				//Loop through the children, and if any of them are functions that are terminal, truncate.
				//To explain this further, consider the following:
				//For the code: concat(die(), msg('')), this diagram shows the abstract syntax tree:
				//         (concat)
				//        /        \
				//       /          \
				//     (die)       (msg)
				//By looking at the code, we can tell that msg() will never be called, because die() will run first,
				//and since it is a "terminal" function, any code after it will NEVER run. However, consider a more complex condition:
				// if(@input){ die() msg('1') } else { msg('2') msg('3') }
				//              if(@input)
				//        [true]/         \[false]
				//             /           \
				//         (sconcat)     (sconcat)
				//           /   \         /    \
				//          /     \       /      \
				//       (die) (msg[1])(msg[2]) (msg[3])
				//In this case, only msg('1') is guaranteed not to run, msg('2') and msg('3') will still run in some cases.
				//So, we can optimize out msg('1') in this case, which would cause the tree to become much simpler, therefore a worthwile optimization:
				//              if(@input)
				//        [true]/        \[false]
				//             /          \
				//          (die)      (sconcat)
				//                      /    \
				//                     /      \
				//                 (msg[2]) (msg[3])
				//We do have to be careful though, because of functions like if, which actually work like this:
				//if(@var){ die() } else { msg('') }
				//                (if)
				//              /  |  \
				//             /   |   \
				//          @var (die) (msg)
				//We can't git rid of the msg() here, because it is actually in another branch.
				//For the time being, we will simply say that if a function uses execs, it
				//is a branch (branches always use execs, though using execs doesn't strictly
				//mean you are a branch type function).

				outer:
				for (int i = 0; i < children.size(); i++) {
					ParseTree t = children.get(i);
					if (t.getData() instanceof CFunction) {
						if (((CFunction)t.getData()).isProcedure() || (func != null && func.useSpecialExec())) {
							continue outer;
						}
						Function f = (Function) FunctionList.getFunction(t.getData());
						Set<Optimizable.OptimizationOption> options = NO_OPTIMIZATIONS;
						if (f instanceof Optimizable) {
							options = ((Optimizable) f).optimizationOptions();
						}
						if (options.contains(Optimizable.OptimizationOption.TERMINAL)) {
							if (children.size() > i + 1) {
								//First, a compiler warning
								CHLog.GetLogger().CompilerWarning(CompilerWarning.UnreachableCode, "Unreachable code. Consider removing this code.", children.get(i + 1).getTarget(), tree.getFileOptions());
								//Now, truncate the children
								for (int j = i + 1; j < children.size(); j++) {
									children.remove(j);
								}
								break outer;
							}
						}
					}
				}

				Function f = ((CFunction)tree.getData()).getFunction();
				if (f instanceof CodeBranch) {
					CodeBranch cb = (CodeBranch)f;
					//Go ahead and depth first optimize the non code branch parts
					List<Integer> branches = Arrays.asList(cb.getCodeBranches(tree.getChildren()));
					for(int i = 0; i < tree.getChildren().size(); i++){
						if(!branches.contains(i)){
							ParseTree child = tree.getChildAt(i);
							try{
								optimize03(child, optimizeProcs);
							} catch(PullMeUpException e){
								tree.getChildren().set(i, e.getNode());
							}
						}
					}
					
					env.getEnv(CompilerEnvironment.class).setFileOptions(tree.getFileOptions());
					tempNode = cb.optimizeDynamic(tree.getTarget(), env, tree.getChildren());

					if (tempNode == Optimizable.PULL_ME_UP) {
						tempNode = tree.getChildAt(0);
					}
					if (tempNode == Optimizable.REMOVE_ME) {
						tree.setData(new CVoid(Target.UNKNOWN));
						tree.removeChildren();
					} else if (tempNode != null) {
						tree.setData(tempNode.getData());
						tree.setChildren(tempNode.getChildren());
					}
				}
				
				//TODO: Check for boolean optimizations here
			}
		} while (tempNode != null && tempNode.numberOfChildren() != numChildren); //Keep optimizing until we made no code branch changes
		//Now optimize the children
		for (int i = 0; i < tree.getChildren().size(); i++) {
			ParseTree node = tree.getChildAt(i);
			optimize02(node, compilerEnvironment, optimizeProcs);
		}
	}

	/**
	 * This pass runs the optimization of the remaining functions.
	 * This gets run three times, once before proc optimizations (to optimize inside
	 * procedures), once after (to optimize down procedure usages),
	 * and a final third time, for the benefit of functions that can pull up or
	 * otherwise reoptimize now that some procs may be gone.
	 *
	 * @param tree
	 * @param compilerEnvironment
	 * @throws ConfigCompileException
	 */
	private void optimize03(ParseTree tree, boolean optimizeProcs) throws ConfigCompileException, PullMeUpException {
		
		if(!(tree.getData() instanceof CFunction)){
			//Not a function, no optimization needed
			return;
		}
		CompilerEnvironment cEnv = env.getEnv(CompilerEnvironment.class);
		if(optimizeProcs && tree.getData() instanceof CFunction && proc.equals(tree.getData().val())){
			//Different way to optimize these, but it won't happen the first go through
			//It is a procedure, so we need to drop down into it (adding it first to the stack
			//in case of recursion) then call optimize03 again, then pop.
			String name = tree.getChildAt(0).getData().primitive(tree.getTarget()).castToString();
			List<IVariable> ivars = new ArrayList<IVariable>();
			for(int i = 1; i < tree.getChildren().size() - 1; i++){
				ParseTree v = tree.getChildAt(i);
				//(const) assignments and ivars are allowed here only
				Mixed var;
				if(v.getData() instanceof CFunction && assign.equals(((CFunction)v.getData()).getFunction().getName())){
					//It's an assign, so grab the second element from it, and check if it's const
					//If so, just toss the value, we don't need it right now. If not, throw a compile error
					//TODO: Actually, we need to check if the value is immutable, not const, but whatever for now.
					if(!v.getChildAt(1).isConst()){
						throw new ConfigCompileException("Default values in a procedure must be constant", v.getTarget());
					}
					var = v.getChildAt(0).getData();
				} else {
					var = v.getData();
				}
				if(!(var instanceof IVariable)){
					throw new ConfigCompileException("Procedure defined incorrectly. Expected a variable, but got a " + v.getChildAt(0).getData().typeName(), v.getTarget());
				}
				ivars.add((IVariable) var);
			}
			cEnv.addProcedure(new Procedure(name, ivars, tree.getChildAt(tree.getChildren().size() - 1), tree.getTarget()));
			cEnv.pushProcedureScope();
			//Now drop into the proc's actual code
			optimize03(tree.getChildAt(tree.getChildren().size() - 1), optimizeProcs);
			cEnv.popProcedureScope();
		} else {
			//Depth first
			for(int i = 0; i < tree.numberOfChildren(); i++){
				ParseTree child = tree.getChildAt(i);
				try{
					optimize03(child, optimizeProcs);
				} catch(PullMeUpException e){
					tree.getChildren().set(i, e.getNode());
				}
			}
			//If this is a procedure, then optimizeProcs is false, so we just need to continue past it for now.
			//(The contents will have already been optimized)
			if(tree.getData() instanceof CFunction && ((CFunction)tree.getData()).isProcedure()){
				if(optimizeProcs){
					//If this is not a valid procedure at this point, this will throw the exception for us.
					cEnv.getProcedure(tree.getData().val(), tree.getTarget());
				}
				return;
			}
			Function func = ((CFunction)tree.getData()).getFunction();
			if(func instanceof Optimizable){
				Optimizable f = (Optimizable)func;
				Set<Optimizable.OptimizationOption> options = f.optimizationOptions();
				if(options.contains(Optimizable.OptimizationOption.OPTIMIZE_DYNAMIC)){
					ParseTree tempNode;
					try{
						cEnv.setFileOptions(tree.getFileOptions());
						tempNode = f.optimizeDynamic(tree.getTarget(), env, tree.getChildren());
					} catch(ConfigRuntimeException e){
						//Turn this into a compile exception, then rethrow
						throw new ConfigCompileException(e);
					}
					if(tempNode == Optimizable.PULL_ME_UP){
						//We're fully done with this function now, because it's completely
						//gone from the tree, so actually we need to replace us with the child
						//in our parent's child list, but we don't have access to that information,
						//so throw an exception up with the child, and the parent will have to
						//deal with it.
						throw new PullMeUpException(tree.getChildAt(0));
					} else if(tempNode == Optimizable.REMOVE_ME){
						tree.setData(new CVoid(tree.getTarget()));
						tree.removeChildren();
					} else if(tempNode != null){
						tree.setData(tempNode.getData());
						tree.setChildren(tempNode.getChildren());
					} //else it was just a compile check
				}
				
				//Now that we have done all the optimizations we can with dynamic functions,
				//let's see if we are constant, and then optimize const stuff
				if(options.contains(OptimizationOption.OPTIMIZE_CONSTANT)
						|| options.contains(OptimizationOption.CONSTANT_OFFLINE)){
					Mixed [] constructs = new Mixed[tree.getChildren().size()];
					for(int i = 0; i < tree.getChildren().size(); i++){
						constructs[i] = tree.getChildAt(i).getData();
						if(constructs[i].isDynamic()){
							//Can't optimize any further, so return
							return;
						}
					}
					try{
						Mixed result;
						if(options.contains(OptimizationOption.CONSTANT_OFFLINE)){
							result = f.exec(tree.getData().getTarget(), env, constructs);
						} else {
							result = f.optimize(tree.getData().getTarget(), env, constructs);
						}
						//If the result is null, it was just a check, it can't optimize further
						if(result != null){
							tree.setData(result);
							tree.removeChildren();
						}
					} catch(ConfigRuntimeException e){
						throw new ConfigCompileException(e);
					}
				}
			}
		}
	}

	/**
	 * This pass ensures no violations of strict mode, if strict mode is enabled.
	 * This includes use of uninitialized variables. For the first call, send a new List for assignments. Scopes
	 * will be handled appropriately.
	 */
	private void optimize04(ParseTree tree, Environment compilerEnvironment, List<String> assignments) throws ConfigCompileException {
		//Depth first, except for code branches, which have to have the non-branches initialized first.
		//We still go through all the motions, even if strict mode is off, because only part
		//of the tree may be in strict mode, in which case we will simply skip throwing the exception
		//if we aren't in strict mode.
		if(tree.getFileOptions().isStrict()){
			
		}
	}

	/**
	 * This optimization level adds all known instances of procs to the
	 * environment. After this pass, all procs, if not obtainable, are a compile
	 * error. Additionally during this stage, tail call recursion is optimized.
	 * An overview of tail call recursion can be found here: http://en.wikipedia.org/wiki/Tail_call
	 *
	 * @param tree
	 * @param compilerEnvironment
	 * @throws ConfigCompileException
	 */
	private void optimize05(ParseTree tree, Environment compilerEnvironment) throws ConfigCompileException {
		
	}
	
	/**
	 * Used by {@link #optimize03(com.laytonsmith.core.ParseTree, com.laytonsmith.core.environments.Environment, boolean)}
	 */
	private class PullMeUpException extends Exception{
		private ParseTree tree;
		public PullMeUpException(ParseTree tree){
			this.tree = tree;
		}
		
		public ParseTree getNode(){
			return tree;
		}
	}

}
