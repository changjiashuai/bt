package bt.processor.torrent;

import bt.data.Bitfield;
import bt.processor.BaseProcessingStage;
import bt.processor.ProcessingStage;
import bt.runtime.Config;
import bt.torrent.BitfieldBasedStatistics;
import bt.torrent.TorrentDescriptor;
import bt.torrent.TorrentRegistry;
import bt.torrent.data.DataWorker;
import bt.torrent.data.IDataWorkerFactory;
import bt.torrent.messaging.Assignments;
import bt.torrent.messaging.BitfieldConsumer;
import bt.torrent.messaging.GenericConsumer;
import bt.torrent.messaging.IncompletePiecesValidator;
import bt.torrent.messaging.MetadataProducer;
import bt.torrent.messaging.PeerRequestConsumer;
import bt.torrent.messaging.PieceConsumer;
import bt.torrent.messaging.RequestProducer;
import bt.torrent.selector.PieceSelector;
import bt.torrent.selector.ValidatingSelector;

import java.util.function.Predicate;

public class InitializeTorrentProcessingStage<C extends TorrentContext> extends BaseProcessingStage<C> {

    private TorrentRegistry torrentRegistry;
    private IDataWorkerFactory dataWorkerFactory;
    private Config config;

    public InitializeTorrentProcessingStage(ProcessingStage<C> next,
                                            TorrentRegistry torrentRegistry,
                                            IDataWorkerFactory dataWorkerFactory,
                                            Config config) {
        super(next);
        this.torrentRegistry = torrentRegistry;
        this.dataWorkerFactory = dataWorkerFactory;
        this.config = config;
    }

    @Override
    protected void doExecute(C context) {
        TorrentDescriptor descriptor = torrentRegistry.register(context.getTorrent().get(), context.getStorage());
        descriptor.start();

        Bitfield bitfield = descriptor.getDataDescriptor().getBitfield();
        BitfieldBasedStatistics pieceStatistics = createPieceStatistics(bitfield);
        PieceSelector selector = createSelector(context.getPieceSelector(), bitfield);

        DataWorker dataWorker = createDataWorker(descriptor);
        Assignments assignments = new Assignments(bitfield, selector, pieceStatistics, config);

        context.getRouter().registerMessagingAgent(GenericConsumer.consumer());
        context.getRouter().registerMessagingAgent(new BitfieldConsumer(bitfield, pieceStatistics));
        context.getRouter().registerMessagingAgent(new PieceConsumer(bitfield, dataWorker));
        context.getRouter().registerMessagingAgent(new PeerRequestConsumer(dataWorker));
        context.getRouter().registerMessagingAgent(new RequestProducer(descriptor.getDataDescriptor()));
        context.getRouter().registerMessagingAgent(new MetadataProducer(() -> context.getTorrent().orElse(null), config));

        context.setBitfield(bitfield);
        context.setAssignments(assignments);
        context.setPieceStatistics(pieceStatistics);
    }

    private BitfieldBasedStatistics createPieceStatistics(Bitfield bitfield) {
        return new BitfieldBasedStatistics(bitfield);
    }

    private PieceSelector createSelector(PieceSelector selector,
                                         Bitfield bitfield) {
        Predicate<Integer> validator = new IncompletePiecesValidator(bitfield);
        return new ValidatingSelector(validator, selector);
    }

    private DataWorker createDataWorker(TorrentDescriptor descriptor) {
        return dataWorkerFactory.createWorker(descriptor.getDataDescriptor());
    }
}
