# QMQ Broker代码学习笔记

首先还是要先看看[架构概览](../docs/cn/arch.md)，里边介绍了存储模型。

无疑broker是QMQ中最核心也是最复杂的组件，趁着五一假期学习了一下主流程，还有很多内容来不及看，这里简单记一下。

### 和Kafka/RocketMQ的模型的区别
正如上文中介绍，大家基于log的基本模型是一致的（具体存储上Kafka是每个partition对应一个文件，
而QMQ和RocketMQ一样是把所有的message添加到同一个文件中），区别在于是否要在一个subject下面分partition。

Kafka和RocketMQ都采用了partition，好处是模型简单，每个partition由一个consumer来处理，只需要记录一个offset就足够了；
此外这种方式做顺序消息的支持也是相对比较方便的，只需要把关联的消息都放到同一个partition中再由consumer顺序消费即可。
主要的问题在于consumer扩缩容不太方便，尤其是Kafka，因为partition的数目还会影响到IO性能。

![arch](../docs/images/arch3.png)

QMQ的方案是在consume log(记录一个subject下面的所有消息的offset)之外，为每个consumer记录一个pull log，
把每个consumer拉取的消息都记录下来。

我觉得这可以理解为另外一个维度的"partition"，就是根据consumer来划分messages，而不是根据message自身的内容来划分。
这个方案还是挺巧妙的解决了扩缩容的问题，但是代价在于维护pull log需要额外的IO操作。
此外因为没有了全局的ack offset，在处理consumer离线的情况也需要一些特殊处理，后面会提到。

### 底层存储怎么实现的

服务端的存储都是在qmq-store模块里边实现。

broker一共维护四种log
- message log, 这个就是存储所有消息内容
- action log, 记录所有的action，包括， PULL, RANGE_ACK 和 FOREVER_OFFLINE
- consumer log, 记录一个subject下面的message offset
- pull log, 记录一个consumer对应的pull

所有的存储都由DefaultStorage来管理，对于某一个特定的log，又分为多个LogSegment, 有一个LogManager来管理。
每个LogSegment对应一个固定长度的物理文件，比如对于message log其LogSegment大小默认限制为1G。
LogManager会记录这些LogSegment存储的文件夹以及文件大小，并使用一个ConcurrentSkipListMap来记录offset到segment的映射，
便于根据offset快速定位到一个segment。

DefaultStorage中的操作基本就是对于某一类日志的读写，比如对于message核心就是两个操作
- `PutMessageResult appendMessage(final RawMessage message)`
append操作中就是把序列化的message写入到log中：
  - 如果当前LogSegment为空，就会创建一个新的LogSegment然后进行操作。
  - 如果当前LogSegment的剩余空间放不下当前message，会先填充剩余空间，然后创建一个新的LogSegment进行操作。
  - 正常情况下，就是把message里header和body对应的size和内容等信息写入到日志中。

- `GetMessageResult pollMessages(String subject, long startSequence, int maxMessages)`
这个操作就是从某个sequence开始读取一定数量的messages
  - 首先根据参数中的sequence，从consumer log中定位到相应位置，然后从当前LogSegment中拿到包含数据的byte buffer
  - 从包含consumer log的byte buffer中，依次解析出maxMessages个consumer log，
    每一个都包含了一条message在message log中的offset和size，因此可以相应的取出message放入到结果集中

这里有两个注意的地方
  - 第一步从一个consumer log的LogSegment取数据时，会通过slice()操作拿到底层的MappedByteBuffer，这里并不会出现数据拷贝。
    在第二步中真正读取consumer log的时候才可能触发文件的IO。
  - 因为consumer log是从单个LogSegment中获取的，有可能取不到所需的maxMessages这么多条数据，
     这种情况下并不会继续向下读取，而只是简单的返回能够取到的数据。
 
### 本地存储是如何持久化的？

QMQ用了NIO的内存映射文件来实现文件读写，但是写入文件并不代表着马上会写到磁盘上，还是需要定期的刷新系统的Page Cache，保证真正写入的磁盘。
因此需要定期对FileChannel调用force()接口，当然底层都是调用了Linux的fsync/fdatasync接口。

上面提到过QMQ维护了四种日志，分别是message,action,consumer,pull，它们的地位其实是不一样的。
- message和action的日志直接对应了用户的操作，可以认为是第一手的数据，因此它们的安全级别其实更高一些。
  对于message和action存储，这部分工作由PeriodFlushService来提供，默认是每500ms刷盘一次。这个基本上和mysql的默认操作(每秒刷盘)类似。
- consumer log和pull log并不是由用户操作直接触发的，而是分别由message log和action log的事件进一步触发的，可以认为是前两种日志的snapshot，
  理论上可以由前两种日志的内容恢复而来，因此在安全要求上会低一些。
  对应这两种日志，有专门的PullLogFlusher、ConsumerLogFlusher进行处理。
  而刷盘的触发条件有两种，一是达到一定的事件次数（默认10000次，可配置），二是达到一定的时间间隔（目前是1分钟）。

这些flusher都是在ServerWrapper中的`initStorage()`来启动。

由于consumer log和pull log的刷盘频率较低，因此有可能当broker从故障中重启的时候，这两者会落后于message log及action log的进度。
因此broker启动的时候会有一个恢复状态的过程，分别由MessageLogIterateService和ActionLogIterateService通过扫描message log和action log来恢复consumer log及pull log。

不过这样就有了另外一个问题，即不能从头扫描日志，而是希望从一个比较近的checkpoint开始扫描。
因此broker中又通过CheckpointManager来记录message log和action log的进度。
MessageCheckpoint就是记录message log的sequence，ActionCheckpoint则记录了action log的sequence，以及各个consumer的pull和ack的位置。
而这两个checkpoint同样是监听message log和action log的事件来进行更新，并且会和consumer log及pull log以同样的频率刷盘，
这样理论上启动的时候最多只需要扫描最近10000条（或1分钟）的新日志即可完成重建。

## broker是怎么启动的？

broker相关的代码入口在qmq-server模块中，不过里边一些核心的代码比如存储其实抽出了独立的模块。

broker的启动流程就在ServerWrapper.start()方法中，我加了一点注释
```
    public void start() {
        LOG.info("qmq server init started");
        register();       // 向meta-server注册当前的broker
        createStorage();  // 准备storage 
        startSyncLog();   // slave开始监听同步消息
        initStorage();    // 初始化本地storage相关逻辑，包括启动flusher和iterate服务
        startServeSync(); // master开始主从同步
        startServerHandlers();  // 启动请求处理的processer
        startConsumerChecker(); // 启动对于consumer的定期状态检查
        addToResources();
        online();         // 更新broker状态，开始服务
        LOG.info("qmq server init done");
    }
```

在`startServerHandlers()`中，会针对broker处理的请求类型注册相应的processor，主要是四种请求
- pull，对应client从broker拉取message
- send，对应client发送一条或多条messages
- ack，对应client通知broker已经处理成功的message区间
- consume_manage, 这个主要是管理员用于重置当前consumer log的offset

### Pull Message的流程是怎么样的？

Pull流程主要操作在PullMessageWorker.process()中，这里使用了这里定义的Actor System，可以参考[这里](actor.md)

其流程主要包括
1. 校验请求
1. 调用`store.findMessages()`来获取messages
  - 首先检查当前consumer有没有尚未ack的messages，如果有的话就返回这些messages。
    内存中对于每个consumer都使用一个ConsumerSequence的结构来记录pull和ack的位置，因此简单比较即能找到相应的messages。
  - 否则从consumer log中获取新的尚未pull的messages，就是使用上面说的`storage.pollMessages()`接口。
    内存中维护了一个ConsumeQueue的结构，记录每一个consumer log的当前offset，因此可以根据这个offset来获取后面的messages。
    操作完成后，当然也需要更新相应的ConsumeQueue和ConsumerSequence。
  - 获取的消息会根据message tag进行过滤。
  - 如果能够获取到messages，就可以直接返回了
1. 如果消息为空，会suspend当前Actor，等待通知
  - 采用类似long polling的机制，把pull请求先暂存下来，等到超时或者有新的messages的时候会唤醒当前的Actor。
  - 这种方式使得新的messages到达的时候，能够及时返回给consumer进行处理

   
### Send Message的流程是怎么样的？

代码流程主要在SendMessageProcessor和SendMessageWorker，核心的部分就在`SendMessageWorker.doInvoke()`，光看代码就很清晰了。
```
    private void doInvoke(ReceivingMessage message) {
        if (BrokerConfig.isReadonly()) {
            brokerReadOnly(message);
            return;
        }

        if (bigSlaveLag()) {
            brokerReadOnly(message);
            return;
        }

        final String subject = message.getSubject();
        if (isIllegalSubject(subject)) {
            if (isRejectIllegalSubject()) {
                notAllowed(message);
                return;
            }
        }

        try {
            // 存储message
            ReceiveResult result = messageStore.putMessage(message);
            // 设置结果并同步到slave
            offer(message, result);
        } catch (Throwable t) {
            error(message, t);
        }
    }
```

其中messageStore.putMessage()就是会调用storage.appendMessage()来写日志文件。

在写完message log后，会发一个MessageLogMeta事件，BuildConsumerLogEventListener会监听这个事件，并相应的写入consumer log。
  
### Ack Message的流程是怎么样的？
ack流程非常简单，就是把ack写入到日志当中，并更新一下相应的ConsumerSequence。
会发出相应的ActionEvent来触发其他的一些操作，比如更新checkpoint。

### 如何处理consumer崩溃的情况
一般来说同一个consumer group中会有多个consumer，如果一个consumer崩溃了，可能会出现某些message已经被拉取但没有被处理的情况，需要由其他的consumer接着处理。
当然broker并不知道那条消息是否已经被处理，只能根据ack的记录来判断是否需要重新分配，
如果consumer是刚好在发送ack之前崩溃，难免就会出现重复pull。当然本身重复消息的问题就是需要client来处理的。

在broker中，每个consumer group都维护了其处理进度，由`ConsumeQueue`来记录，其实就是记录下一次pull的时候从哪个sequence开始。注意这里并没有记录ack的进度。
每个consumer则分别记录了各自pull和ack的进度，由`ConsumerSequence`记录，显然这里pull的进度和pull log是对应的。
而ack的进度自然是由client上报的，而且必须是递增的，每次上报完成的sequence的区间。
比如说client拉取了1-3三条消息，可能先处理完了3，但是此时不会上报ack 3，必须在1-2也处理完成之后才会ack 1-3。

有一个问题是，从consumer group的角度看，ack的顺序并不是递增的。
比如consumer1可能先拿了1-3三条消息，consumer2后拿了4-6三条消息。
之后consumer2成功处理并ack了这三条消息，但是consumer1在ack之前就下线了，这个时候其pull log中已经记录了1-3。
这种情况下该如何让消息1-3重新得到处理呢？
一种方案是把consumer group的pull进度回退到没有ack的消息之前，从而使得这些消息能够被重新pull，不过这样可能导致大量的消息被重复处理。
QMQ的实现挺巧妙的，不需要回退pull进度，而是直接ack这些消息，然后把它们放入到retry queue里，从而使其可以被pull。

这部分逻辑在`RetryTask.run()`方法中，大致流程如下
```
   void run() {
        ... // 省略
        while (true) {
            ... // 省略
            final long firstNotAckedSequence = consumerSequence.getAckSequence() + 1;
            final long lastPulledSequence = consumerSequence.getPullSequence();
            if (lastPulledSequence < firstNotAckedSequence) return;

            LOG.info("put need retry message in retry task, subject: {}, group: {}, consumerId: {}, ack offset: {}, pull offset: {}",
                    subscriber.getSubject(), subscriber.getGroup(), subscriber.getConsumerId(), firstNotAckedSequence, lastPulledSequence);
            consumerSequenceManager.putNeedRetryMessages(subscriber.getSubject(), subscriber.getGroup(), subscriber.getConsumerId(), firstNotAckedSequence, firstNotAckedSequence);

            // put ack action
            ... // 省略
        }
    }
```

触发RetryTask的逻辑在SubscriberStatusChecker中。这里一个subscriber就是一个consumer。
broker启动的时候，会调用startConsumerCheck()，设置每分钟检查各个consumer的状态，
一旦发现consumer三分钟没有响应就认为是OFFLINE状态，并触发`RetryTask.run()`。

### 小结
必须说QMQ的代码质量确实很高，不论是架构还是命名还是流程都算得上很清晰，在了解了基本设计后，花几天功夫应该是能够把主流程看明白的。
不过也有一些缺憾，主要是代码中设计文档和注释都比较少，网上现有的代码分析也好像没怎么看到，因此很多实现逻辑还是只能靠从代码中来猜。
我猜部分原因可能是因为QMQ开源不久，一些内部的文档还没有整理好公开。
这篇里很多内容也是连蒙带猜的，还请大家指正。

另外一个问题就是当前基本没有测试用例。
确实服务端有大量的异步流程，测试不是那么好写，但是这还是给理解带来不少难度，尤其是要上手改个什么东西的话心里完全没底。
据刀总说内部还是有集成测试用例的，只是还没有开源出来，应该是由于依赖了去哪儿内部的测试框架。
还是希望这部分能够继续完善，后续让大家也可以上手来改点东西。

最后提一个小坑，当前的client实现中，consumerId完全是根据ip地址来生成的，我理解这是为了consumerId在重启后能够保持稳定。
但是这导致本机即使起两个consumer的进程也会被当做同一个consumerId，导致各种offset都乱掉了，很不利于本地调试。
写了一个小的[hack](https://github.com/fankaidev/qmq/commit/eddeb48776753b4c865d3f9271dcc4ef1abfbd73)，
来给consumerId加上随机，大家本地调试的话可以使用。

