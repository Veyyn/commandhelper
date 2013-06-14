package com.laytonsmith.core.natives.annotations;

import com.laytonsmith.annotations.documentation;
import com.laytonsmith.annotations.typename;
import com.laytonsmith.core.CHVersion;
import com.laytonsmith.core.natives.MClass;

/**
 *
 */
@typename("CrossCast")
public class CrossCast extends MAnnotation {
	
	@documentation(docs="The value(s) that this object can be cross cast to. If it can be multiple values, use a disjoint type.", since= CHVersion.V3_3_1)
	public MClass value;

	public String docs() {
		return "When tagged to a class, this allows a class to cross cast from the given types. Generally, you should only cross cast from primitives, if at all.";
	}

	@Override
	public MAnnotation[] getMetaAnnotations() {
		return new MAnnotation[]{
			new TargetRestriction(ElementType.TYPE)
		};
	}

	public CHVersion since() {
		return CHVersion.V3_3_1;
	}
	
}
