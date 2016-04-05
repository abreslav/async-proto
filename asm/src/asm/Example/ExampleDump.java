package asm.example;

import org.objectweb.asm.*;

public class ExampleDump implements Opcodes {

    public static byte[] dump() throws Exception {

        ClassWriter cw = new ClassWriter(0);
        FieldVisitor fv;
        MethodVisitor mv;
        AnnotationVisitor av0;

        cw.visit(V1_6, ACC_PUBLIC + ACC_FINAL + ACC_SUPER, "example/Example", null, "java/lang/Object", null);

        cw.visitSource("example.kt", "SMAP\nexample.kt\nKotlin\n*S Kotlin\n*F\n+ 1 example.kt\nexample/Example\n+ 2 Console.kt\nkotlin/io/ConsoleKt\n*L\n1#1,25:1\n78#2,2:26\n72#2,2:28\n*E\n");

        {
            av0 = cw.visitAnnotation("Lkotlin/Metadata;", true);
            av0.visit("mv", new int[]{1, 1, 0});
            av0.visit("bv", new int[]{1, 0, 0});
            av0.visit("k", new Integer(1));
            {
                AnnotationVisitor av1 = av0.visitArray("d1");
                av1.visit(null, "\u0000\u001c\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\u0008\u0002\n\u0002\u0010\u0002\n\u0002\u0008\u0002\n\u0002\u0010\u0008\n\u0002\u0008\u0003\u0018\u00002\u00020\u0001B\u0005\u00a2\u0006\u0002\u0010\u0002J\u0006\u0010\u0003\u001a\u00020\u0004J\u000e\u0010\u0005\u001a\u00020\u00042\u0006\u0010\u0006\u001a\u00020\u0007J\u0010\u0010\u0008\u001a\u00020\u00042\u0008\u0010\u0009\u001a\u0004\u0018\u00010\u0001\u00a8\u0006\n");
                av1.visitEnd();
            }
            {
                AnnotationVisitor av1 = av0.visitArray("d2");
                av1.visit(null, "Lexample/Example;");
                av1.visit(null, "");
                av1.visit(null, "()V");
                av1.visit(null, "main");
                av1.visit(null, "");
                av1.visit(null, "p");
                av1.visit(null, "i");
                av1.visit(null, "");
                av1.visit(null, "printObject");
                av1.visit(null, "o");
                av1.visit(null, "asm");
                av1.visitEnd();
            }
            av0.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_FINAL, "main", "()V", null, null);
            mv.visitCode();
            Label l0 = new Label();
            Label lcatch = new Label();
            Label l1 = new Label();
            mv.visitTryCatchBlock(l0, l1, l1, "java/lang/IllegalStateException");
            Label l2 = new Label();
            mv.visitTryCatchBlock(l0, l1, l2, "java/lang/IllegalArgumentException");
            Label l3 = new Label();
            mv.visitLabel(l3);
            mv.visitLineNumber(5, l3);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l4 = new Label();
            mv.visitLabel(l4);
            mv.visitLineNumber(6, l4);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("HERE");
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "printObject", "(Ljava/lang/Object;)V", false);


            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, 1);
            mv.visitJumpInsn(GOTO, lcatch);



            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn("HERE0");
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "printObject", "(Ljava/lang/Object;)V", false);
            mv.visitLabel(l0);
            mv.visitLineNumber(7, l0);
            mv.visitInsn(NOP);
            Label l5 = new Label();
            mv.visitLabel(l5);
            mv.visitLineNumber(8, l5);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_3);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l6 = new Label();
            mv.visitLabel(l6);
            mv.visitLineNumber(9, l6);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_4);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l7 = new Label();
            mv.visitLabel(l7);
            mv.visitLineNumber(10, l7);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_5);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l8 = new Label();
            mv.visitLabel(l8);
            mv.visitLineNumber(11, l8);
            mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
            mv.visitInsn(ATHROW);
            mv.visitLabel(l1);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/IllegalStateException"});
            mv.visitVarInsn(ASTORE, 1);
            Label l9 = new Label();
            mv.visitLabel(l9);
            mv.visitLineNumber(13, l9);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitIntInsn(BIPUSH, 6);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l10 = new Label();
            mv.visitLabel(l10);


            mv.visitLabel(lcatch);





            mv.visitLineNumber(14, l10);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "printObject", "(Ljava/lang/Object;)V", false);
            Label l11 = new Label();
            mv.visitLabel(l11);
            mv.visitLineNumber(15, l11);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitIntInsn(BIPUSH, 8);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l12 = new Label();
            mv.visitLabel(l12);
            Label l13 = new Label();
            mv.visitJumpInsn(GOTO, l13);
            mv.visitLabel(l2);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/IllegalArgumentException"});
            mv.visitVarInsn(ASTORE, 1);
            Label l14 = new Label();
            mv.visitLabel(l14);
            mv.visitLineNumber(17, l14);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitIntInsn(BIPUSH, 9);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l15 = new Label();
            mv.visitLabel(l15);
            mv.visitLineNumber(18, l15);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitIntInsn(BIPUSH, 10);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            Label l16 = new Label();
            mv.visitLabel(l16);
            mv.visitLineNumber(19, l16);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitIntInsn(BIPUSH, 11);
            mv.visitMethodInsn(INVOKEVIRTUAL, "example/Example", "p", "(I)V", false);
            mv.visitLabel(l13);
            mv.visitLineNumber(20, l13);
            mv.visitLineNumber(21, l13);
            mv.visitFrame(Opcodes.F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null);
            mv.visitInsn(RETURN);
            Label l17 = new Label();
            mv.visitLabel(l17);
            mv.visitLocalVariable("e", "Ljava/lang/IllegalStateException;", null, l1, l12, 1);
            mv.visitLocalVariable("e", "Ljava/lang/IllegalArgumentException;", null, l2, l13, 1);
            mv.visitLocalVariable("this", "Lexample/Example;", null, l3, l17, 0);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_FINAL, "p", "(I)V", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(23, l0);
            mv.visitInsn(NOP);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLineNumber(26, l1);
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
            Label l2 = new Label();
            mv.visitLabel(l2);
            mv.visitLineNumber(27, l2);
            mv.visitLineNumber(23, l2);
            mv.visitInsn(RETURN);
            Label l3 = new Label();
            mv.visitLabel(l3);
            mv.visitLocalVariable("$i$f$println", "I", null, l1, l2, 2);
            mv.visitLocalVariable("this", "Lexample/Example;", null, l0, l3, 0);
            mv.visitLocalVariable("i", "I", null, l0, l3, 1);
            mv.visitMaxs(2, 3);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC + ACC_FINAL, "printObject", "(Ljava/lang/Object;)V", null, null);
            {
                av0 = mv.visitParameterAnnotation(0, "Lorg/jetbrains/annotations/Nullable;", false);
                av0.visitEnd();
            }
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(24, l0);
            mv.visitInsn(NOP);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLineNumber(28, l1);
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/Object;)V", false);
            Label l2 = new Label();
            mv.visitLabel(l2);
            mv.visitLineNumber(29, l2);
            mv.visitLineNumber(24, l2);
            mv.visitInsn(RETURN);
            Label l3 = new Label();
            mv.visitLabel(l3);
            mv.visitLocalVariable("$i$f$println", "I", null, l1, l2, 2);
            mv.visitLocalVariable("this", "Lexample/Example;", null, l0, l3, 0);
            mv.visitLocalVariable("o", "Ljava/lang/Object;", null, l0, l3, 1);
            mv.visitMaxs(2, 3);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(3, l0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(RETURN);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLocalVariable("this", "Lexample/Example;", null, l0, l1, 0);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        cw.visitEnd();

        return cw.toByteArray();
    }
}
