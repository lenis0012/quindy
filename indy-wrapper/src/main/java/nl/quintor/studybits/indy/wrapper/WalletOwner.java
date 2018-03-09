package nl.quintor.studybits.indy.wrapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nl.quintor.studybits.indy.wrapper.dto.*;
import nl.quintor.studybits.indy.wrapper.util.JSONUtil;
import org.hyperledger.indy.sdk.IndyException;
import org.hyperledger.indy.sdk.crypto.Crypto;
import org.hyperledger.indy.sdk.did.Did;
import org.hyperledger.indy.sdk.ledger.Ledger;
import org.hyperledger.indy.sdk.pairwise.Pairwise;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static nl.quintor.studybits.indy.wrapper.util.AsyncUtil.wrapException;
import static nl.quintor.studybits.indy.wrapper.util.AsyncUtil.wrapPredicateException;

@Slf4j
public class WalletOwner {
    IndyPool pool;
    IndyWallet wallet;
    @Getter
    String name;

    public WalletOwner(String name, IndyPool pool, IndyWallet wallet) {
        this.name = name;
        this.pool = pool;
        this.wallet = wallet;
    }

    CompletableFuture<String> signAndSubmitRequest(String request) throws IndyException {
        return signAndSubmitRequest(request, wallet.getMainDid());
    }

    CompletableFuture<String> signAndSubmitRequest(String request, String did) throws IndyException {
        return Ledger.signAndSubmitRequest(pool.getPool(), wallet.getWallet(), did, request);
    }

    public CompletableFuture<AnoncryptedMessage> acceptConnectionRequest(ConnectionRequest connectionRequest) throws JsonProcessingException, IndyException, ExecutionException, InterruptedException {
        log.debug("{} Called acceptConnectionRequest with {}, {}, {}", name, pool.getPool(), wallet.getWallet(), connectionRequest);

        return anoncrypt(wallet.newDid()
                .thenApply(
                        (myDid) -> new ConnectionResponse(myDid.getDid(), myDid.getVerkey(), connectionRequest.getNonce(), connectionRequest.getDid()))
                .thenCompose(wrapException((ConnectionResponse connectionResponse) ->
                        getKeyForDid(connectionRequest.getDid())
                        .thenCompose(wrapException(key -> storeDidAndPairwise(connectionResponse.getDid(), connectionRequest.getDid(), connectionResponse.getVerkey(), key)))
                        .thenApply((_void) -> connectionResponse))));
    }

    CompletableFuture<Void> storeDidAndPairwise(String myDid, String theirDid, String myKey, String theirKey)  throws JsonProcessingException, IndyException {
        log.debug("{} Called storeDidAndPairwise: myDid: {}, theirDid: {}", name, myDid, theirDid);

        return Did.storeTheirDid(wallet.getWallet(), new TheirDidInfo(theirDid, theirKey).toJSON())
                .thenCompose(wrapException(
                        (storeDidResponse) -> {
                            log.debug("{} Creating pairwise theirDid: {}, myDid: {}, metadata: {}", name, theirDid, myDid, "");
                            return Pairwise.createPairwise(wallet.getWallet(), theirDid, myDid,"");

                        }));
    }

    CompletableFuture<GetPairwiseResult> getPairwiseByTheirDid(String theirDid) throws IndyException {
        log.debug("{} Called getPairwise by their did: {}", name, theirDid);
        return Pairwise.getPairwise(wallet.getWallet(), theirDid)
                .thenApply(wrapException(json -> JSONUtil.mapper.readValue(json, GetPairwiseResult.class)));
    }

    CompletableFuture<String> getKeyForDid(String did) throws IndyException {
                log.debug("{} Called getKeyForDid: {}", name, did);
                return Did.keyForDid(pool.getPool(), wallet.getWallet(), did)
                        .thenApply(key -> {
                            log.debug("{} Got key for did {} key {}", name, did, key);
                            return key;
                        });

    }


    private CompletableFuture<AnoncryptedMessage> anoncrypt(CompletableFuture<? extends AnonCryptable> messageFuture) throws JsonProcessingException, IndyException {
        return messageFuture.thenCompose(wrapException(
                (AnonCryptable message) -> {
                    log.debug("{} Anoncrypting message: {}, with did: {}", name, message.toJSON(), message.getTheirDid());
                    return getKeyForDid(message.getTheirDid())
                            .thenCompose(wrapException((key) -> {
                                log.debug("{} Anoncrypting with key: {}", name, key);
                                return Crypto.anonCrypt(key, message.toJSON().getBytes(Charset.forName("utf8")))
                                        .thenApply((byte[] cryptedMessage) -> new AnoncryptedMessage(cryptedMessage, message.getTheirDid()));
                            }))
                            ;
                }
        ));
    }

    <T extends AnonCryptable> CompletableFuture<T> anonDecrypt(AnoncryptedMessage message, Class<T> valueType) throws IndyException {
        return getKeyForDid(message.getTargetDid())
                .thenCompose(wrapException(key -> Crypto.anonDecrypt(wallet.getWallet(), key, message.getMessage())))
                .thenApply(wrapException((decryptedMessage) -> JSONUtil.mapper.readValue(new String(decryptedMessage, Charset.forName("utf8")), valueType)));
    }

    CompletableFuture<AuthcryptedMessage> authcrypt(CompletableFuture<? extends AuthCryptable> messageFuture) throws JsonProcessingException, IndyException {
        return messageFuture.thenCompose(wrapException(
                (AuthCryptable message) -> {
                    log.debug("{} Authcrypting message: {}, myDid: {}, theirDid: {}", name, message.toJSON(), message.getMyDid(), message.getTheirDid());
                    return getKeyForDid(message.getMyDid())
                            .thenCompose(wrapException(myKey -> {
                                return getKeyForDid(message.getTheirDid())
                                        .thenCompose(wrapException((String theirKey) -> {
                                                    log.debug("{} Authcrypting with keys myKey {}, theirKey {}", name, myKey, theirKey);
                                                    return Crypto.authCrypt(wallet.getWallet(), myKey, theirKey, message.toJSON().getBytes(Charset.forName("utf8")))
                                                            .thenApply(cryptedMessage -> new AuthcryptedMessage(cryptedMessage, message.getMyDid()));
                                                })
                                        );
                            }));


                }
        ));
    }

    <T extends AuthCryptable> CompletableFuture<T> authDecrypt(AuthcryptedMessage message, Class<T> valueType) throws IndyException {
        return getPairwiseByTheirDid(message.getDid())
                .thenCompose(wrapException(pairwiseResult -> getKeyForDid(pairwiseResult.getMyDid())))
                .thenCompose(wrapException(key -> Crypto.authDecrypt(wallet.getWallet(), key, message.getMessage())
                .thenApply(wrapException((decryptedMessage) -> {
                    assert decryptedMessage.getVerkey().equals(key);
                    T decryptedObject = JSONUtil.mapper.readValue(new String(decryptedMessage.getDecryptedMessage(), Charset.forName("utf8")), valueType);
                    decryptedObject.setTheirDid(message.getDid());
                    return decryptedObject;
                }))));
    }
}