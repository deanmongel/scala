/**
 *  $Id$
 */

package scalac.transformer.matching ;

import scalac.*;
import scalac.ast.*;
import scalac.symtab.*;
import Tree.*;

import scalac.transformer.TransMatch.Matcher ;

import java.util.* ;
import Scope.SymbolIterator;

import scalac.ast.printer.TextTreePrinter ;

import scalac.util.Name ;
import scalac.util.Names ;

import ch.epfl.lamp.util.Position;

public class RightTracerInScala extends TracerInScala  {

    //Scope  scp;
    //Symbol vars[];

    Vector seqVars;
    Vector allVars;

    Matcher _m;

    //      Symbol funSym;

    Symbol elemSym;
    Symbol targetSym;

    HashMap helpMap2 ;
    Vector  helpVarDefs;


    /** translate right tracer to code
     * @param dfa determinized left tracer
     * @param left nondeterm. left tracer
     * @param cf   ...
     * @param pat  ?
     * @param elementType ...
     */
    public RightTracerInScala( DetWordAutom dfa,
			       NondetWordAutom left,
			       Matcher m,
			       CodeFactory cf,
			       Tree pat,
			       Type elementType ) {
	super( dfa, elementType, m.owner, cf );
	this._m = m;

	Vector seqVars = new Vector();

	for( int j = 0; j < left.nstates; j++ ) {
	    if( left.qbinders[ j ] != null )
		for( Iterator it = left.qbinders[ j ].iterator();
		     it.hasNext() ; ) {
		    Symbol varSym = (Symbol) it.next();

		    if( !seqVars.contains( varSym ) )
			seqVars.add( varSym );
		}
	}

	this.seqVars = seqVars;
	this.allVars = CollectVariableTraverser.collectVars( pat );

	helpMap2 = new HashMap();
	helpVarDefs = new Vector();

	for( Iterator it = seqVars.iterator(); it.hasNext(); ) {
	    makeHelpVar( (Symbol) it.next() );
	}

	for( Iterator it = allVars.iterator(); it.hasNext(); ) {
	    Symbol varSym = (Symbol) it.next();
	    if( !seqVars.contains( varSym )) {
		makeHelpVar( varSym, true );
	    }
	}

	initializeSyms();
    }

    void makeHelpVar( Symbol realVar ) {
	makeHelpVar( realVar, false );
    }

    /** makes a helpvar and puts mapping into helpMap, ValDef into helpVarDefs
     */

    void makeHelpVar( Symbol realVar, boolean keepType ) {
	Symbol helpVar = new TermSymbol( pos,
					 cf.fresh.newName( realVar.name
							   .toString() ),
					 owner,
					 0);

	//System.out.println("making helpvar : "+realVar+" -> "+helpVar);

	if( keepType )
	    helpVar.setType( realVar.type() );
	else
	    helpVar.setType( cf.SeqListType( elementType ) );


	helpMap.put( realVar, helpVar );

	Tree rhs;
	if( keepType )
	    rhs = gen.mkDefaultValue(cf.pos,
				     realVar.type()); //cf.ignoreValue( realVar.type() );
	else
	    rhs = /* cf.newRef(  cf.newSeqNil( */ gen.Nil( cf.pos )
		.setType( cf.SeqListType( elementType ));
 /* ) */;
	helpVar.flags |= Modifiers.MUTABLE;
	Tree varDef = gen.ValDef( helpVar, rhs );
	//((ValDef) varDef).kind = Kinds.VAR;
	helpVarDefs.add( varDef );

    }

    Tree prependToHelpVar( Symbol realVar, Tree elem ) {
	Tree hv = refHelpVar( realVar );
	return gen.Assign( hv, gen.Cons(cf.pos, elem.type(), elem, hv));//cf.newSeqCons( elem, hv ));
	/*
	  return cf.Block(pos,
	  new Tree [] {
	  cf.debugPrintRuntime( "ASSIGN" ),
	  gen.Assign( hv, cf.newSeqCons( elem, hv ))
	  }, defs.UNIT_TYPE);
	*/
    }

    protected void initializeSyms() {

	this.funSym = newFunSym( "binder" );

	this.iterSym = new TermSymbol( pos,
				       cf.fresh.newName("iter"),
				       funSym,
				       0)
	    .setType( cf.SeqTraceType( elementType ));

	this.stateSym = new TermSymbol( pos,
					cf.fresh.newName("q"),
					funSym,
					0 )
	    .setType( defs.INT_TYPE ) ;

	this.elemSym = new TermSymbol( pos,
				       cf.fresh.newName("elem"),
				       funSym,
				       0)
	    .setType( elementType ) ;

	this.targetSym = new TermSymbol( pos,
					 cf.fresh.newName("trgt"),
					 funSym,
					 0)
	    .setType( defs.INT_TYPE ) ;


	funSym.setType( new Type.MethodType( new Symbol[] {
	    iterSym, stateSym },  defs.UNIT_TYPE ));

    }

    // same as in LeftTracer
    Tree code_fail() {

	return cf.ThrowMatchError( _m.pos, defs.UNIT_TYPE );

    }

    public Tree code_body() {

	Tree body = code_fail(); // never reached at runtime.

	// state [ nstates-1 ] is the dead state, so we skip it

	//`if( state == q ) <code_state> else {...}'
	for( int i = dfa.nstates-1; i >= 0; i-- ) {
	    body = code_state( i, body );
	}


	Tree t3 = cf.If( cf.isEmpty( _iter() ),
			 run_finished( 0 ),
			 gen.mkBlock( new Tree[] {
			     gen.ValDef( targetSym,
					 cf.SeqTrace_headState( gen.Ident( pos, iterSym))),
			     gen.ValDef( elemSym,
					 cf.SeqTrace_headElem( gen.Ident( pos, iterSym))),

			     body }));

	/*
	  t3 = gen.mkBlock( new Tree[] {
	  cf.debugPrintRuntime("enter binderFun"),
	  cf.debugPrintRuntime(" state:"),
	  cf.debugPrintRuntime( gen.Ident( pos, stateSym )),
	  cf.debugPrintRuntime(" iter:"),
	  cf.debugPrintRuntime(_iter()),
	  cf.debugPrintNewlineRuntime(""),
	  t3 });
	*/

	//System.out.println("enter RightTracerInScala:code_body()");// DEBUG
	//System.out.println("dfa.nstates"+dfa.nstates);// DEBUG
	return t3;
    }

    /** this one is special, we check the first element of the trace
     *  and choose the next state depending only on the state part
     */
    Tree code_state0( Tree elseBody ) { // careful, map Int to Int

	HashMap hmap    = (HashMap) dfa.deltaq( 0 ); // all the initial states

	Tree stateBody = code_fail(); // never happens

	for( Iterator it = hmap.keySet().iterator(); it.hasNext(); ) {
	    Integer targetL  = (Integer) it.next();
	    Integer targetR  = (Integer) hmap.get( targetL );

	    stateBody = cf.If( cf.Equals( gen.Ident( pos, targetSym ),
					  gen.mkIntLit( cf.pos, targetL )),
			       callFun( new Tree[] {
				   cf.SeqTrace_tail( _iter() ),
				   gen.mkIntLit( cf.pos, targetR ) }),
			       stateBody );
	}

	return cf.If( cf.Equals( _state(), gen.mkIntLit( cf.pos, 0 )),
		      stateBody ,
		      elseBody );

    }

    Tree code_state( int i, Tree elseBody ) {

	if( i == 0 )
	    return code_state0( elseBody );

	int  finalSwRes;
	Tree stateBody ; // action(delta) for one particular label/test

	// default action (fail if there is none)

	stateBody = code_delta( i, Label.DefaultLabel);

	if( stateBody == null )
	    stateBody = code_fail();

	// transitions of state i

	HashMap trans = ((HashMap[])dfa.deltaq)[ i ];

	for( Iterator labs = dfa.labels.iterator(); labs.hasNext() ; ) {
	    Object label = labs.next();
	    Integer next = (Integer) trans.get( label );

	    Tree action = code_delta( i, (Label) label );

	    if( action != null ) {

		stateBody = cf.If( _cur_eq( _iter(), (Label) label ),
				   action,
				   stateBody);
	    }
	}

	return cf.If( cf.Equals( _state(), gen.mkIntLit( cf.pos, i )),
		      stateBody ,
		      elseBody );
    }

    /** returns a Tree whose type is boolean.
     *  now we care about free vars
     */

    Tree handleBody1( HashMap helpMap3  ) {
	//System.out.println("Rtis.handleBody ... helpMap = " + helpMap );
	// todo: change helpMap s.t. symbols are not reused.

	Tree res[] = new Tree[ helpMap3.keySet().size() + 1 ];
	int j = 0;
	for( Iterator it = helpMap3.keySet().iterator(); it.hasNext(); ) {
	    Symbol vsym = (Symbol) it.next();
	    Symbol hv   = (Symbol) helpMap3.get( vsym );
	    hv.setType( cf.SeqListType( elementType ) ) ;
	    Tree refv   = gen.Ident(Position.FIRSTPOS, vsym);
	    Tree refhv  = gen.Ident(Position.FIRSTPOS, hv);
	    res[ j++ ] = gen.Assign( refhv, refv );
	}

	res[ j ] = gen.mkBooleanLit( Position.FIRSTPOS, true ); // just `true'

	return gen.mkBlock(res);
    }

    // calling the AlgebraicMatcher here
    Tree _cur_match( Tree pat ) {

	//System.out.println("RTiS._cur_match("+pat.toString()+")");

	//System.out.println("calling algebraic matcher on type:"+pat.type);

	Matcher m = new Matcher( funSym,//this.funSym,
				 currentElem(),
				 defs.BOOLEAN_TYPE );

	// there could be regular expressions under Sequence node, export those later
	Vector varsToExport = NoSeqVariableTraverser.varsNoSeq( pat );
	HashMap freshenMap = new HashMap();

	HashMap helpMap3 = new HashMap();

	// "freshening": never use the same symbol more than once (in later invocations of _cur_match)

	for( Iterator it = varsToExport.iterator(); it.hasNext(); ) {
	    Symbol key = (Symbol) it.next();
	    this.helpMap2.put( key, helpMap.get( key ));
	    // "freshening"
	    Symbol newSym = key.cloneSymbol();
	    newSym.name = key.name.append( Name.fromString("gu234") ); // is fresh now :-)
	    freshenMap.put( key, newSym );
	    helpMap3.put( newSym, helpMap.get( key ));
	}

	//System.out.println("RightTracerInScala::freshenMap :"+freshenMap);
	//System.out.println("RightTracerInScala:: -pat :"+pat.toString());
	//System.out.println("RightTracerInScala::varsToExport :"+varsToExport);


	// "freshening"
	TreeCloner st = new TreeCloner(cf.unit.global, freshenMap, Type.IdMap );
	pat = st.transform( pat );
	//System.out.println("RightTracerInScala:: -pat( after subst ) :"+pat);

	// val match { case <pat> => { <do binding>; true }
	//             case _     => false

	am.construct( m, new CaseDef[] {
	    (CaseDef) cf.make.CaseDef( pat.pos,
				       pat,           // if tree val matches pat -> update vars, return true
				       Tree.Empty,
				       handleBody1( helpMap3 )/* "freshening */),
	    (CaseDef) cf.make.CaseDef( pat.pos,
				       cf.make.Ident( pat.pos, Names.WILDCARD )
				       //DON'T .setSymbol( Symbol.NONE ) !!FIXED
				       .setType( pat.type() ),
				       Tree.Empty,
				       gen.mkBooleanLit( pat.pos, false )) }, // else return false
		      true // do binding please
		      );

	return  am.toTree().setType( defs.BOOLEAN_TYPE );
    }


    /** returns translation of transition with label from i.
     *  returns null if there is no such transition(no translation needed)
     */
    Tree code_delta( int i, Label label ) {
	HashMap hmap    = (HashMap) dfa.deltaq( i );
	Integer ntarget = (Integer) hmap.get( label );
	Tree    algMatchTree = null;
	if( ntarget == null )
	    return null;
	//System.out.println("delta("+i+","+label+")" );
	Label theLab = null;
	switch(label) {
	case Label.Pair( Integer state, Label lab2 ):
	    //assert ntarget == state;
	    theLab = lab2;
	    switch( lab2 ) {
	    case TreeLabel( Tree pat ):
		algMatchTree = _cur_match( pat );
		break;
	    }
	    break;
	case DefaultLabel:
	    throw new ApplicationError(); // should not happen
	}
	assert dfa.qbinders != null : "qbinders ?";

	Vector vars = dfa.qbinders[ i ];

	if( vars == null ) vars = new Vector(); // TODO: make this more consistent
	assert vars != null;

	//System.out.println("delta: theLab: " + theLab + " vars in current ="+ vars );

	if( ntarget == null )
	    return code_fail();

	Tree stms[] = new Tree[ vars.size()
				+ ((algMatchTree != null )? 1 : 0 )
				+ 1 ];
	int j = 0;
	for( Iterator it = vars.iterator(); it.hasNext(); ) {
	    Symbol var = (Symbol) it.next();

	    Tree rhs = gen.Ident( pos, elemSym );

	    stms[ j++ ] = prependToHelpVar( var , rhs);
	}

	if( algMatchTree != null )
	    stms[ j++ ] = algMatchTree ;

	stms[ j ] = callFun( new Tree[] { cf.SeqTrace_tail( _iter() ),
					  gen.mkIntLit( cf.pos, ntarget ) } );

	return gen.mkBlock( pos, stms );
    }

    /* returns statements that do the work of the right-transducer
     */
    Tree[] getStms( Tree trace ) {

	//System.out.println( "!!getStms.helpVarDefs: "+helpVarDefs);

	Vector v = new Vector();

	for( Iterator it = helpVarDefs.iterator(); it.hasNext(); )
	    v.add( (Tree) it.next() );

	v.add( gen.DefDef( this.funSym, code_body() )  );
	v.add( callFun( new Tree[] {  trace, gen.mkIntLit( cf.pos, 0 )  }  )  );

	/*
	  for(Iterator it = helpMap.keySet().iterator(); it.hasNext(); ) {
	  // DEBUG
	  Symbol var = (Symbol)it.next();
	  v.add( cf.debugPrintRuntime( var.name.toString() ));
	  v.add( cf.debugPrintRuntime( refHelpVar( var )) );
	  }
	*/

	for( Iterator it = seqVars.iterator(); it.hasNext(); ) {
	    v.add( bindVar( (Symbol) it.next() ) );
	}

	Tree result[] = new Tree[ v.size() ];
	int j = 0;
	for( Iterator it = v.iterator(); it.hasNext(); ) {
	    result[ j++ ] = (Tree) it.next();
	}

	// helpvarSEQ via _m.varMap

	return result;

    }

    /** return the accumulator. (same as in LeftTracerInScala)
     *  todo: move tree generation of Unit somewhere else
     */
    Tree run_finished( int state ) {
	return gen.Block(Position.FIRSTPOS, Tree.EMPTY_ARRAY).setType( defs.UNIT_TYPE );
    }

    Tree current() {
	return gen.Ident( pos, targetSym );
    }

    Tree currentElem() {
	return gen.Ident( pos, elemSym );
    }

    Tree _cur_eq( Tree iter, Label label ) {
	//System.out.println("_cur_eq, thelab: "+label);
	switch( label ) {
	case Pair( Integer target, Label theLab ):
	    return cf.Equals( gen.mkIntLit( cf.pos, target ),
			      current() );
	}
	throw new ApplicationError("expected Pair label");
    }


}
