package com.self.distribut.txtransactional.aspect;


import com.self.distribut.txtransactional.annotation.TxTransactional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class TxTransactionAspect implements Ordered{


    @Around("@annotation(com.self.distribut.txtransactional.annotation.TxTransactional)")
    public void invoke(ProceedingJoinPoint point ){

        MethodSignature signature =(MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        TxTransactional txTransactional = method.getAnnotation(TxTransactional.class);

        System.out.println( "Aspect:"+ txTransactional.isStart() );

        try {
            // 走spring的逻辑 ，比spring优先级低
            point.proceed();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

    }


    @Override
    public int getOrder() {
        return 10000;
    }
}