/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003, University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.daveho.ba;

import java.util.*;
import org.apache.bcel.Constants;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;

public class InnerClassAccessMap {
	private Map<String, Map<String, InnerClassAccess>> classToAccessMap;

	private InnerClassAccessMap() {
		this.classToAccessMap = new HashMap<String, Map<String, InnerClassAccess>>();
	}

	private static InnerClassAccessMap instance = new InnerClassAccessMap();

	public static InnerClassAccessMap instance() { return instance; }

	private static int toInt(byte b) {
		int value = b & 0x7F;
		if ((b & 0x80) != 0)
			value |= 0x80;
		return value;
	}

	private static int getIndex(byte[] instructionList, int index) {
		return (toInt(instructionList[index+1]) << 8) | toInt(instructionList[index+2]);
	}

	private static class InstructionCallback implements BytecodeScanner.Callback {
		private JavaClass javaClass;
		private String methodName;
		private String methodSig;
		private byte[] instructionList;
		private InnerClassAccess access;
		private int accessCount;

		public InstructionCallback(JavaClass javaClass, String methodName, String methodSig, byte[] instructionList) {
			this.javaClass = javaClass;
			this.methodName = methodName;
			this.methodSig = methodSig;
			this.instructionList = instructionList;
			this.access = null;
			this.accessCount = 0;
		}

		public void handleInstruction(int opcode, int index) {
			switch (opcode) {
			case Constants.GETFIELD:
			case Constants.PUTFIELD:
				setField(getIndex(instructionList, index), false, opcode == Constants.GETFIELD);
				break;
			case Constants.GETSTATIC:
			case Constants.PUTSTATIC:
				setField(getIndex(instructionList, index), true, opcode == Constants.GETSTATIC);
				break;
			}
		}

		public InnerClassAccess getAccess() {
			return access;
		}

		private void setField(int cpIndex, boolean isStatic, boolean isLoad) {
			// We only allow one field access for an accessor method.
			accessCount++;
			if (accessCount != 1) {
				access = null;
				return;
			}

			ConstantPool cp = javaClass.getConstantPool();
			ConstantFieldref fieldref = (ConstantFieldref) cp.getConstant(cpIndex);

			ConstantClass cls = (ConstantClass) cp.getConstant(fieldref.getClassIndex());
			String className = cls.getBytes(cp);

			ConstantNameAndType nameAndType = (ConstantNameAndType) cp.getConstant(fieldref.getNameAndTypeIndex());
			String fieldName = nameAndType.getName(cp);
			String fieldSig = nameAndType.getSignature(cp);

			XField xfield = isStatic
				? (XField) new StaticField(className, fieldName, fieldSig)
				: (XField) new InstanceField(className, fieldName, fieldSig);

			if (isValidAccessMethod(methodSig, xfield, isLoad))
				access = new InnerClassAccess(methodName, methodSig, xfield, isLoad);
		}

		private boolean isValidAccessMethod(String methodSig, XField field, boolean isLoad) {
			// Figure out what the expected method signature should be
			String classSig = "L" + javaClass.getClassName().replace('.', '/') + ";";
			StringBuffer buf = new StringBuffer();
			buf.append('(');
			String fieldSig = field.getFieldSignature();
			if (!field.isStatic())
				buf.append(classSig); // the OuterClass.this reference
			if (!isLoad)
				buf.append(field.getFieldSignature()); // the value being stored
			buf.append(')');
			buf.append(field.getFieldSignature()); // all accessors return the contents of the field

			String expectedMethodSig = buf.toString();

			if (!expectedMethodSig.equals(methodSig)) {
/*
				System.err.println("In " + javaClass.getClassName() + "." + methodName + " expected " +
					expectedMethodSig + ", saw " + methodSig);
				System.err.println(isLoad ? "LOAD" : "STORE");
*/
				return false;
			}

			return true;
		}
	}

	private static final Map<String, InnerClassAccess> emptyMap = new HashMap<String, InnerClassAccess>();

	/**
	 * Return a map of inner-class member access method names to
	 * the fields that they access for given class name.
	 * @param className the name of the class
	 * @return map of access method names to the fields they access
	 */
	public Map<String, InnerClassAccess> getAccessMapForClass(String className)
		throws ClassNotFoundException {

		Map<String, InnerClassAccess> map = classToAccessMap.get(className);
		if (map == null) {
			map = new HashMap<String, InnerClassAccess>();
			JavaClass javaClass = Repository.lookupClass(className);

			Method[]  methodList = javaClass.getMethods();
			for (int i = 0; i < methodList.length; ++i) {
				Method method = methodList[i];
				String methodName = method.getName();
				if (!methodName.startsWith("access$"))
					continue;

				Code code = method.getCode();
				if (code == null)
					continue;

				byte[] instructionList = code.getCode();
				String methodSig = method.getSignature();
				InstructionCallback callback = new InstructionCallback(javaClass, methodName, methodSig, instructionList);
				new BytecodeScanner().scan(instructionList, callback);
				InnerClassAccess access = callback.getAccess();
				if (access != null)
					map.put(methodName, access);
			}

			if (map.size() == 0)
				map = emptyMap;
		}

		return map;
	}

}

// vim:ts=4
