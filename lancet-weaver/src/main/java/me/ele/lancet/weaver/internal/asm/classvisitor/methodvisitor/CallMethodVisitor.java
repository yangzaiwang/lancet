package me.ele.lancet.weaver.internal.asm.classvisitor.methodvisitor;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

import me.ele.lancet.weaver.internal.asm.ClassCollector;
import me.ele.lancet.weaver.internal.asm.ClassTransform;
import me.ele.lancet.weaver.internal.entity.CallInfo;
import me.ele.lancet.weaver.internal.log.Log;
import me.ele.lancet.weaver.internal.util.AopMethodAdjuster;

/**
 * Created by Jude on 17/4/26.
 */
public class CallMethodVisitor extends MethodVisitor {

    private final Map<String, List<CallInfo>> matchMap;
    private String currentClassName;
    private ClassCollector classCollector;

    public CallMethodVisitor(MethodVisitor mv, Map<String, List<CallInfo>> matchMap, String currentClassName, ClassCollector classCollector) {
        super(Opcodes.ASM5, mv);
        this.matchMap = matchMap;
        this.currentClassName = currentClassName;
        this.mv = mv;
        this.classCollector = classCollector;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        List<CallInfo> infos = matchMap.get(owner + " " + name + " " + desc);
        if (infos != null) {
            // begin hook this code.
            MethodInsnNode node = new MethodInsnNode(opcode,owner,name,desc,itf);
            proxy(infos, node);
            node.accept(this);
            return;
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }


    private void proxy(List<CallInfo> infos, MethodInsnNode methodInsnNode){
        for (int i = 0; i < infos.size(); i++) {
            Log.tag("transform").i("start weave Call method: "+currentClassName+".?"+" for "+infos.get(i).targetClass+"."+infos.get(i).targetMethod);
            proxyOne(infos.get(i),methodInsnNode,i);
        }
    }

    /**
     * This method will handle a CallInfo.
     * first, generate a innerClass,just like this.
     * copy the method from Hook class to the innerClass:
     *
     *    private static class _lancet {
     *        public static void com_sample_hook_call_Hook2_putCoffee(Cup cup, String coffee) {
     *            System.out.println("replace " + coffee + " with Cappuccino before add to cup");
     *            coffee = "Cappuccino";
     *            cup.putCoffee(coffee);
     *        }
     *    }
     *
     * and then,change the invoke code to invoke the innerClass method,like this:
     *
     *    public Cup brew(Cup cup) {
     *        CoffeeMaker._lancet.com.sample.hook.call.Hook2_putCoffee(cup, this.coffeeBox.getLatte());
     *        return cup;
     *    }
     *
     *
     * @param info hook info entry
     * @param methodInsnNode the code to invoke target method
     * @param index index of CallInfo in CallInfo List
     */
    private void proxyOne(CallInfo info, MethodInsnNode methodInsnNode,int index){
        // all visitor will share the only one innerclass
        ClassWriter writer = classCollector.getInnerClassWriter(ClassTransform.AID_INNER_CLASS_NAME);

        String innerClassName = classCollector.getCanonicalName(ClassTransform.AID_INNER_CLASS_NAME);
        // every method in innerclass will add source class name as prefix
        String methodName = info.sourceClass.replace(".","_")+"_"+info.sourceMethod.name;

        MethodNode proxyMethod = new MethodNode(Opcodes.ASM5, Opcodes.ACC_STATIC, methodName, info.sourceMethod.desc, info.sourceMethod.signature, ((List<String>)info.sourceMethod.exceptions).toArray(new String[0]));
        // write origin method code to proxyMethod, and change the Origin.call() to invoke target method.
        info.sourceMethod.accept(new MethodVisitor(Opcodes.ASM5,proxyMethod) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {

                if (opcode == AopMethodAdjuster.OP_FLAG){
                    // invoke target method
                    opcode = methodInsnNode.getOpcode();
                    owner = methodInsnNode.owner;
                    name = methodInsnNode.name;
                    desc = methodInsnNode.desc;
                    itf = methodInsnNode.itf;

                    // load all arguments.
                    Type[] types = Type.getMethodType(proxyMethod.desc).getArgumentTypes();
                    int index = 0;
                    for (int i = 0; i < types.length; i++) {
                        super.visitVarInsn(types[i].getOpcode(Opcodes.ILOAD), index);
                        index += types[i].getSize();
                    }
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return null;
            }

            /**
             * override this method to delete 'this' var in method when origin method is nor static.
             * 'this' var is always at index 0 and length is 1.
             * So minus 1 when origin method is nor static.
             */
            @Override
            public void visitVarInsn(int opcode, int var) {
                if ((info.sourceMethod.access & Opcodes.ACC_STATIC)==0){
                    var--;
                }
                super.visitVarInsn(opcode, var);
            }

            /**
             * edit the LocalVariable to delete 'this' var when origin method is nor static.
             */
            @Override
            public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
                if ((info.sourceMethod.access & Opcodes.ACC_STATIC)==0){
                    if (name.equals("this")){
                        return;
                    }else {
                        index--;
                    }
                }
                super.visitLocalVariable(name, desc, signature, start, end, index);
            }
        });

        proxyMethod.accept(writer);

        // redirection the invoke code to proxy method in innerclass.
        methodInsnNode.setOpcode(Opcodes.INVOKESTATIC);
        methodInsnNode.owner = innerClassName;
        methodInsnNode.name = proxyMethod.name;
        methodInsnNode.desc = proxyMethod.desc;
    }

}
